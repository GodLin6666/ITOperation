package com.opsai.rag;

import java.util.List;
import java.util.Locale;

/**
 * 运行平台：Windows 桌面、Linux 服务器，以及不区分平台的通用知识。
 * <p>
 * 企业里两类问题的排查手法差别很大：Windows 看事件查看器 ID、服务依赖、组策略；
 * Linux 看 systemd 状态、journalctl、OOM killer、inode。同一套知识库混在一起检索，
 * 经常把 Linux 的命令喂给桌面问题，所以这里给知识条目和故障单都加上平台维度。
 */
public final class Platform {

    public static final String ANY = "ANY";
    public static final String WINDOWS = "WINDOWS";
    public static final String LINUX = "LINUX";

    /** 明显的 Linux 特征词：出现在现象或日志里就加分 */
    private static final List<String> LINUX_KEYS = List.of(
            "systemd", "systemctl", "journalctl", "dmesg", "oom-kill", "out of memory",
            "/var/log", "/etc/", "/usr/", "/opt/", "df -h", "df -i", "free -m", " ss -", "netstat",
            "load average", "inode", "crontab", "sudo", "bash:", "kernel:", "segfault",
            "permission denied", "no space left", "ulimit", "nohup", "yum ", "apt-get", "dnf ",
            "nginx:", "mysql:", "redis-server");

    /** 明显的 Windows 特征词 */
    private static final List<String> WINDOWS_KEYS = List.of(
            "windows", "win32", "win64", "event id", "eventid", "事件 id", "事件id",
            "事件查看器", "eventvwr", "wmi", "蓝屏", "bsod", "kernel-power", "services.msc",
            "组策略", "gpedit", "注册表", "regedit", "c:\\", "打印机", "spooler", "explorer.exe",
            "svchost", "mstsc", "远程桌面", "应用程序日志", "系统日志", "域登录", "ipconfig",
            "powershell", "任务管理器");

    private Platform() {
    }

    /** 把前端/种子里的各种写法收敛成三个值：WINDOWS / LINUX / ANY */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return ANY;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("WIN")) return WINDOWS;
        if (v.startsWith("LINUX") || v.equals("UNIX") || v.startsWith("SERVER")) return LINUX;
        if (v.equals("DESKTOP") || v.equals("PC")) return WINDOWS;
        return ANY;
    }

    /**
     * 从现象和日志里猜平台。猜不出来返回 ANY，调用方按不区分平台处理。
     * 简单打分：命中一次加一分，谁分高算谁，平手按 ANY 处理。
     */
    public static String detect(String phenomenon, String logText) {
        String text = ((phenomenon == null ? "" : phenomenon) + "\n" + (logText == null ? "" : logText))
                .toLowerCase(Locale.ROOT);
        if (text.isBlank()) return ANY;
        int win = 0, linux = 0;
        for (String k : WINDOWS_KEYS) if (text.contains(k)) win++;
        for (String k : LINUX_KEYS) if (text.contains(k)) linux++;
        if (win == 0 && linux == 0) return ANY;
        if (win == linux) return ANY;
        return win > linux ? WINDOWS : LINUX;
    }

    /** 检索过滤：通用知识对任何平台都可见，专用知识只给自己的平台 */
    public static boolean matches(String docPlatform, String requested) {
        if (requested == null || ANY.equalsIgnoreCase(requested)) return true;
        if (docPlatform == null || docPlatform.isBlank()) return true;
        String d = docPlatform.trim().toUpperCase(Locale.ROOT);
        return ANY.equals(d) || d.equals(requested.trim().toUpperCase(Locale.ROOT));
    }

    public static String label(String p) {
        String v = normalize(p);
        return switch (v) {
            case WINDOWS -> "Windows 桌面";
            case LINUX -> "Linux 服务器";
            default -> "通用";
        };
    }

    /** 提示词里的平台要点：让模型知道该往哪个方向取证 */
    public static String hints(String p) {
        String v = normalize(p);
        if (LINUX.equals(v)) {
            return """
                    【平台要点】Linux 服务器
                    - 先看服务状态：systemctl status <unit>、systemctl --failed
                    - 再看日志：journalctl -u <unit> -n 200 --no-pager、journalctl -p err -b
                    - 资源类：df -h（空间）、df -i（inode）、free -m、uptime（负载）、dmesg -T | tail
                    - 进程类：ps aux --sort=-%cpu | head、ss -tlnp（端口占用）、lsof -p <pid>
                    - 常见根因：OOM killer（dmesg 里的 oom-kill）、磁盘或 inode 满、unit 依赖失败、SELinux 拒绝、时间漂移
                    """;
        }
        if (WINDOWS.equals(v)) {
            return """
                    【平台要点】Windows 桌面
                    - 先看事件查看器：系统日志里的 41（意外断电）、1001（蓝屏）、6008、7000/7009/7031（服务启动失败）、1074
                    - 应用日志里的 1000/1002（应用崩溃）、1026（.NET 异常）
                    - 服务与依赖：services.msc 看启动类型与依赖服务，Print Spooler 卡死会导致打印异常
                    - 系统状态：任务管理器看 CPU/内存/磁盘占用，资源监视器看句柄与网络
                    - 常见根因：驱动冲突、磁盘空间不足、组策略或域信任问题、系统更新失败、配置文件损坏
                    - 远程排障：mstsc 连不上时先查防火墙、RDP 服务与网络级别身份验证
                    """;
        }
        return "";
    }
}
