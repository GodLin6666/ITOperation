package com.opsai.service;

import com.opsai.config.OpsAiProps;
import com.opsai.entity.SysUser;
import com.opsai.repo.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录与 token 管理。
 *
 * <p><b>两种账号并存：</b>
 * <ul>
 *   <li>{@code auth_source=LOCAL}：本系统自己校验 BCrypt 密码。内置的 admin/engineer/viewer
 *       属于这类，作用是**域控故障时的应急入口**。</li>
 *   <li>{@code auth_source=LDAP}：密码交给域控校验，本地只留一条记录用于日志和权限。</li>
 * </ul>
 * 两条路径**严格隔离**：域账号不能用本地密码登录，本地账号也不会去问域控。
 * 这样既避免了「域密码泄露到本地」，也避免域控一挂就全公司都进不来。
 *
 * <p>token 存在内存里（单机够用）。要上多实例时把 {@code tokens} 换成 Redis 即可，
 * 改动只集中在本类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 一个登录会话 */
    public record Session(String username, String realName, String role, long expireAt) {
        public boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private final SysUserRepository userRepo;
    private final OpsAiProps props;
    private final LdapAuthService ldapAuthService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Session> tokens = new ConcurrentHashMap<>();

    /** 登录成功返回 token，失败抛异常 */
    public Map<String, Object> login(String username, String password) {
        String name = username == null ? "" : username.trim();
        SysUser user = userRepo.findByUsername(name).orElse(null);

        if (user != null && user.getStatus() != null && user.getStatus() == 0) {
            throw new IllegalStateException("该账号已被禁用");
        }

        boolean isLdapAccount = user != null && "LDAP".equalsIgnoreCase(user.getAuthSource());

        if (isLdapAccount) {
            return loginViaLdap(name, password, user);
        }
        if (user != null) {
            return loginViaLocal(user, password);
        }
        // 本地没有这个用户：如果开了域认证，就当成域账号来试，成功则自动建档
        if (ldapAuthService.isEnabled()) {
            return loginViaLdap(name, password, null);
        }
        throw new IllegalArgumentException("用户名或密码错误");
    }

    // ---------------- 本地账号 ----------------

    private Map<String, Object> loginViaLocal(SysUser user, String password) {
        if (!encoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return issueToken(user.getUsername(),
                user.getRealName() == null ? user.getUsername() : user.getRealName(),
                user.getRole(), "LOCAL");
    }

    // ---------------- 域账号 ----------------

    private Map<String, Object> loginViaLdap(String username, String password, SysUser existing) {
        LdapAuthService.DomainUser du = ldapAuthService.authenticate(username, password);

        SysUser user = existing;
        if (user == null) {
            // 首次登录：自动建档。password 存随机串，确保它永远不可能被本地密码绕过
            user = new SysUser();
            user.setUsername(du.username());
            user.setPassword(encoder.encode(UUID.randomUUID().toString()));
            user.setRealName(du.displayName());
            user.setRole(du.role());
            user.setStatus(1);
            user.setAuthSource("LDAP");
            user.setCreateTime(LocalDateTime.now());
            userRepo.save(user);
            log.info("[域登录] 首次登录，已自动建档：{}（{}），角色 {}", du.username(), du.displayName(), du.role());
        } else {
            // 老用户：每次登录同步显示名和角色，AD 里调了组，这里下次登录就生效
            String oldRole = user.getRole();
            user.setRealName(du.displayName());
            user.setRole(du.role());
            userRepo.save(user);
            if (!du.role().equals(oldRole)) {
                log.info("[域登录] 角色同步：{} {} -> {}", du.username(), oldRole, du.role());
            }
        }

        return issueToken(user.getUsername(), user.getRealName(), user.getRole(), "LDAP");
    }

    // ---------------- token ----------------

    private Map<String, Object> issueToken(String username, String realName, String role, String source) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long expireAt = System.currentTimeMillis() + props.getAuth().getTokenHours() * 3600_000L;
        tokens.put(token, new Session(username, realName, role, expireAt));

        return Map.of(
                "token", token,
                "username", username,
                "realName", realName,
                "role", role,
                "authSource", source,
                "expireAt", Instant.ofEpochMilli(expireAt).toString());
    }

    /** 校验 token，无效返回 null */
    public Session verify(String token) {
        if (token == null || token.isBlank()) return null;
        Session s = tokens.get(token);
        if (s == null) return null;
        if (s.expired()) {
            tokens.remove(token);
            return null;
        }
        return s;
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }

    /** 生成 BCrypt 密文（初始化管理员账号、加新用户时用） */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public BCryptPasswordEncoder encoder() {
        return encoder;
    }
}
