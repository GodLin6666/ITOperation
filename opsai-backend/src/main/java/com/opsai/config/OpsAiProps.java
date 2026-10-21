package com.opsai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 读取 application.yml 中 opsai.* 的配置。
 * 有了这个类，代码里就不用到处写 @Value，改配置只改 yml。
 */
@Data
@Component
@ConfigurationProperties(prefix = "opsai")
public class OpsAiProps {

    private Ollama ollama = new Ollama();
    private Rag rag = new Rag();
    private Storage storage = new Storage();
    private Auth auth = new Auth();
    private Ldap ldap = new Ldap();

    @Data
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "qwen2.5:7b-instruct-q4_K_M";
        private String embedModel = "bge-m3";
        private double temperature = 0.2;
        private int numCtx = 4096;
        private int timeoutSeconds = 300;
    }

    @Data
    public static class Rag {
        private int topK = 4;
        private double minScore = 0.35;
        private int chunkSize = 400;
        private int chunkOverlap = 80;

        /**
         * 是否把嵌入模型（bge-m3）放到 CPU 上跑。
         * <p>
         * 为什么默认开：本机是 6GB 显存的 4050，qwen2.5-7B 量化后已占约 4.7GB，
         * 只剩 1.2GB 左右。bge-m3 约 1.2GB，两个模型塞不进显存时 Ollama 会把对话模型
         * 换出，下一次对话就要重新从硬盘加载（实测每次多花约 5 秒，首字耗时直接超标）。
         * 把只有 0.5GB 级别计算量的嵌入模型放到 CPU（首次加载约 2 秒、之后每次约 90 毫秒），
         * 显存就完全留给对话模型，首字耗时立刻回到 1 秒级。
         * 如果以后换了大显存显卡，把这里改成 false 即可。
         */
        private boolean embedOnCpu = true;
    }

    @Data
    public static class Storage {
        private String uploadDir = "./data/logs";
    }

    @Data
    public static class Auth {
        private int tokenHours = 12;

        /**
         * 会话存储方式：
         *   memory —— 放进程内存（默认，单后端实例够用）
         *   redis  —— 放 Redis（**多台后端做负载均衡时必须用**，否则会掉线）
         */
        private String store = "memory";
    }

    /**
     * 企业域（AD / OpenLDAP）对接配置。
     * <p>
     * 开启后，员工用**域账号**登录，本系统不再单独维护密码；
     * 登录成功会自动在 sys_user 里建一条 auth_source=LDAP 的记录用于留痕。
     * <p>
     * 安全约定：
     * <ul>
     *   <li>LDAP 用户只能走 LDAP 认证，不允许回落成本地密码；</li>
     *   <li>本地账号（auth_source=LOCAL，如内置 admin）仍然可用，作为域控故障时的应急入口；</li>
     *   <li>域控不可达时不会让整个系统瘫痪，只是 LDAP 用户登不进来。</li>
     * </ul>
     */
    @Data
    public static class Ldap {
        private boolean enabled = false;

        /** 域控地址：ldap://dc01.corp.local:389 或 ldaps://dc01.corp.local:636 */
        private String url = "";
        private String baseDn = "";

        /**
         * 绑定方式，决定「拿什么身份去域控验证密码」：
         * <ul>
         *   <li>{@code UPN} —— zhangsan@corp.local，AD 首选，不需要服务账号</li>
         *   <li>{@code DN}  —— 按 userDnPattern 拼出 DN，OpenLDAP 常用</li>
         *   <li>{@code SEARCH} —— 先用服务账号搜出 DN 再绑定，需要 managerDn</li>
         * </ul>
         * 之所以要三种：AD 默认不允许匿名查，OpenLDAP 也一样，
         * 「先匿名搜再绑定」在不配服务账号的环境里会直接失败。
         */
        private String bindMode = "UPN";

        /** bindMode=UPN 时用，例如 corp.local */
        private String upnSuffix = "";

        /** bindMode=DN 时用，例如 uid={0},ou=People,dc=corp,dc=local */
        private String userDnPattern = "";

        /** 用户查询过滤器，{0} 会被替换成登录名：AD 用 (sAMAccountName={0})，OpenLDAP 用 (uid={0}) */
        private String userFilter = "(sAMAccountName={0})";

        /** 搜索用户的起始 DN，留空则用 baseDn */
        private String userSearchBase = "";

        /** 服务账号，仅 bindMode=SEARCH 时需要 */
        private String managerDn = "";
        private String managerPassword = "";

        /** 取显示名和所属组的属性名 */
        private String displayNameAttr = "displayName";
        private String groupAttr = "memberOf";

        /** 组名匹配规则：memberOf 里包含这个片段就映射成对应角色（不区分大小写） */
        private String adminGroup = "";
        private String engineerGroup = "";

        /** 没匹配到任何组时给的默认角色 */
        private String defaultRole = "viewer";

        /** 连接/查询超时（毫秒） */
        private int timeoutMs = 5000;

        /** ldaps + 域控自签证书时是否跳过证书校验（仅限内网） */
        private boolean trustAllCerts = false;
    }
}
