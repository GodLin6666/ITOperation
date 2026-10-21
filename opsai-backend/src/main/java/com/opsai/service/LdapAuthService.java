package com.opsai.service;

import com.opsai.config.OpsAiProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业域（Active Directory / OpenLDAP）认证。
 *
 * <p><b>整体流程：</b>
 * <ol>
 *   <li>按 bind-mode 算出用户的绑定身份（UPN 或 DN）；</li>
 *   <li>用用户自己的密码建一个连接 —— 建成功即密码正确，本系统全程不接触明文密码；</li>
 *   <li>再读显示名和所属组，映射成本系统的角色。</li>
 * </ol>
 *
 * <p><b>两个实测踩到的坑（都已在代码里规避）：</b>
 * <ul>
 *   <li>不要先匿名搜再绑定：AD 和 OpenLDAP 默认都不允许匿名查询，
 *       会返回 error code 32（noSuchObject）。必须先拿到绑定身份直接绑定。</li>
 *   <li>凭据必须在 afterPropertiesSet() 之前设置：顺序反了的话
 *       LdapContextSource 建出来的连接是匿名的，搜索会静默失败。</li>
 * </ul>
 *
 * <p>读属性优先用服务账号（managerDn）：AD 里普通域用户通常能读自己，
 * 但读 memberOf、跨 OU 的属性经常受限；配一个只读服务账号最稳。
 * 没配时才退回用用户自己的身份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LdapAuthService {

    private final OpsAiProps props;

    /** 域认证结果 */
    public record DomainUser(String username, String displayName, String role) {
    }

    public boolean isEnabled() {
        return props.getLdap().isEnabled();
    }

    /**
     * 用域账号认证。
     *
     * @throws IllegalArgumentException 账号或密码错误
     * @throws IllegalStateException    域控不可达 / 配置有问题
     */
    public DomainUser authenticate(String username, String password) {
        OpsAiProps.Ldap cfg = props.getLdap();
        if (!cfg.isEnabled()) throw new IllegalStateException("未开启域认证");
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("用户名或密码不能为空");
        }

        String bindId = buildBindId(username);

        // ---- 第一步：拿用户自己的密码去绑定。失败就是密码错 ----
        DirContext bound = null;
        try {
            bound = source(null, null).getContext(bindId, password);
        } catch (org.springframework.ldap.AuthenticationException e) {
            throw new IllegalArgumentException("域账号或密码错误");
        } catch (org.springframework.ldap.NamingException e) {
            log.warn("[LDAP] 绑定失败（可能是域控不可达或 DN 规则不对）: {}", e.getMessage());
            throw new IllegalStateException("域认证服务暂时不可用，请稍后再试或联系管理员");
        } finally {
            closeQuietly(bound);
        }

        // ---- 第二步：读显示名和所属组 ----
        String displayName = username;
        List<String> groups = new ArrayList<>();
        try {
            boolean useManager = !blank(cfg.getManagerDn());
            LdapTemplate tpl = new LdapTemplate(useManager
                    ? source(cfg.getManagerDn(), cfg.getManagerPassword())
                    : source(bindId, password));

            String filter = cfg.getUserFilter().replace("{0}", escapeFilter(username));
            String searchBase = blank(cfg.getUserSearchBase()) ? cfg.getBaseDn() : cfg.getUserSearchBase();

            List<Map<String, Object>> rows = tpl.search(searchBase, filter,
                    (AttributesMapper<Map<String, Object>>) attrs -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("display", firstAttr(attrs, cfg.getDisplayNameAttr()));
                        m.put("groups", allAttr(attrs, cfg.getGroupAttr()));
                        return m;
                    });

            if (!rows.isEmpty()) {
                Object d = rows.get(0).get("display");
                if (d != null && !String.valueOf(d).isBlank()) displayName = String.valueOf(d);
                Object g = rows.get(0).get("groups");
                if (g instanceof List<?> list) {
                    for (Object o : list) groups.add(String.valueOf(o));
                }
            }
        } catch (Exception e) {
            log.warn("[LDAP] 读取用户属性失败（角色将用默认值 {}）: {}", cfg.getDefaultRole(), e.getMessage());
        }

        String role = mapRole(groups);
        log.info("[LDAP] 域账号登录成功: {}（{}），角色 {}，组数 {}", username, displayName, role, groups.size());
        return new DomainUser(username, displayName, role);
    }

    // ---------------- 绑定身份 ----------------

    private String buildBindId(String username) {
        OpsAiProps.Ldap cfg = props.getLdap();
        String mode = cfg.getBindMode() == null ? "UPN" : cfg.getBindMode().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "DN" -> {
                if (blank(cfg.getUserDnPattern())) {
                    throw new IllegalStateException("bind-mode=DN 时必须配置 user-dn-pattern");
                }
                yield cfg.getUserDnPattern().replace("{0}", escapeDnValue(username));
            }
            case "SEARCH" -> searchUserDn(username);
            default -> {
                if (blank(cfg.getUpnSuffix())) {
                    throw new IllegalStateException("bind-mode=UPN 时必须配置 upn-suffix");
                }
                yield username + "@" + cfg.getUpnSuffix();
            }
        };
    }

    /** SEARCH 模式：用服务账号搜出用户 DN */
    private String searchUserDn(String username) {
        OpsAiProps.Ldap cfg = props.getLdap();
        if (blank(cfg.getManagerDn())) {
            throw new IllegalStateException("bind-mode=SEARCH 时必须配置 manager-dn / manager-password");
        }
        try {
            LdapTemplate tpl = new LdapTemplate(source(cfg.getManagerDn(), cfg.getManagerPassword()));
            String filter = cfg.getUserFilter().replace("{0}", escapeFilter(username));
            String searchBase = blank(cfg.getUserSearchBase()) ? cfg.getBaseDn() : cfg.getUserSearchBase();

            List<String> dns = tpl.search(searchBase, filter,
                    (AttributesMapper<String>) attrs -> {
                        try {
                            return String.valueOf(attrs.get("distinguishedName").get());
                        } catch (Exception e) {
                            return null;
                        }
                    });
            if (!dns.isEmpty() && dns.get(0) != null && !"null".equals(dns.get(0))) return dns.get(0);
            throw new IllegalArgumentException("域账号或密码错误");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[LDAP] 搜索用户 DN 失败: {}", e.getMessage());
            throw new IllegalStateException("域认证服务暂时不可用");
        }
    }

    // ---------------- 连接源 ----------------

    /**
     * 构建连接源。凭据必须在 afterPropertiesSet() 之前设置 ——
     * 反过来会让建出来的连接变成匿名，搜索静默失败（实测踩过）。
     *
     * @param userDn   绑定身份；为空表示匿名
     * @param password 对应密码
     */
    private LdapContextSource source(String userDn, String password) {
        OpsAiProps.Ldap cfg = props.getLdap();
        LdapContextSource cs = new LdapContextSource();
        cs.setUrl(cfg.getUrl());
        // 刻意不调用 setBase()：设了 base 之后 LdapTemplate.search() 会把传入路径
        // 当成相对路径再拼一次，实测会得到 ou=People,dc=corp,dc=local,dc=corp,dc=local。
        // 这里统一由调用方传绝对搜索基准。

        Hashtable<String, Object> env = new Hashtable<>();
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(cfg.getTimeoutMs()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(cfg.getTimeoutMs()));
        if (cfg.getUrl() != null && cfg.getUrl().toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
            if (cfg.isTrustAllCerts()) {
                env.put("java.naming.ldap.factory.socket", TrustAllSocketFactory.class.getName());
            }
        }
        cs.setBaseEnvironmentProperties(env);

        if (!blank(userDn)) {
            cs.setUserDn(userDn);
            cs.setPassword(password == null ? "" : password);
        }

        try {
            cs.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("LDAP 连接配置有误：" + e.getMessage());
        }
        return cs;
    }

    /** 域控自签证书时跳过校验（仅限内网） */
    public static class TrustAllSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private static final javax.net.ssl.SSLSocketFactory DELEGATE =
                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();

        @Override
        public java.net.Socket createSocket() throws java.io.IOException {
            return DELEGATE.createSocket();
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return DELEGATE.createSocket(s, host, port, autoClose);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return DELEGATE.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return DELEGATE.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return DELEGATE.createSocket(host, port);
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress lh, int lp)
                throws java.io.IOException {
            return DELEGATE.createSocket(host, port, lh, lp);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return DELEGATE.createSocket(host, port);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress a, int port, java.net.InetAddress la, int lp)
                throws java.io.IOException {
            return DELEGATE.createSocket(a, port, la, lp);
        }
    }

    // ---------------- 组 → 角色 ----------------

    private String mapRole(List<String> groups) {
        OpsAiProps.Ldap cfg = props.getLdap();
        for (String g : groups) {
            if (!blank(cfg.getAdminGroup())
                    && g.toLowerCase(Locale.ROOT).contains(cfg.getAdminGroup().toLowerCase(Locale.ROOT))) {
                return "admin";
            }
        }
        for (String g : groups) {
            if (!blank(cfg.getEngineerGroup())
                    && g.toLowerCase(Locale.ROOT).contains(cfg.getEngineerGroup().toLowerCase(Locale.ROOT))) {
                return "engineer";
            }
        }
        return cfg.getDefaultRole();
    }

    // ---------------- 小工具 ----------------

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void closeQuietly(DirContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String firstAttr(Attributes attrs, String name) {
        Attribute a = attrs.get(name);
        if (a == null) return null;
        try {
            Object v = a.get();
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> allAttr(Attributes attrs, String name) {
        List<String> out = new ArrayList<>();
        Attribute a = attrs.get(name);
        if (a == null) return out;
        try {
            NamingEnumeration<?> en = a.getAll();
            while (en.hasMore()) out.add(String.valueOf(en.next()));
        } catch (Exception ignored) {
        }
        return out;
    }

    private String escapeFilter(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeDnValue(String s) {
        return s.replace("\\", "\\\\").replace(",", "\\,").replace("+", "\\+")
                .replace("\"", "\\\"").replace("<", "\\<").replace(">", "\\>")
                .replace(";", "\\;").replace("=", "\\=");
    }
}
