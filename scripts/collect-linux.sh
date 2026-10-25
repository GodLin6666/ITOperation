#!/usr/bin/env bash
# ============================================================
# Linux 服务器信息采集脚本（给排障助手用）
#
# 用法（在服务器上以 root 或具备读取权限的账号运行）：
#   bash collect-linux.sh
#   bash collect-linux.sh > /tmp/linux-info.txt
#
# 输出是一段文本，直接粘进排障助手的「日志」框即可。
# 只读采集：不修改配置、不重启服务、不删除文件。
# ============================================================
set -u

line() { printf "\n### %s\n" "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

echo "==== Linux 服务器采集信息 ===="
echo "采集时间：$(date "+%Y-%m-%d %H:%M:%S %Z")"
echo "主机名：$(hostname)  内核：$(uname -sr)  架构：$(uname -m)"
have uptime && echo "运行时长与负载：$(uptime)"

line "操作系统"
if [ -f /etc/os-release ]; then . /etc/os-release; echo "${PRETTY_NAME:-unknown}"; else uname -a; fi

line "CPU 与负载"
have nproc && echo "逻辑核数：$(nproc)"
have uptime && echo "load average：$(cat /proc/loadavg)"

line "内存"
have free && free -m

line "磁盘空间"
df -hT 2>/dev/null | grep -v -E "tmpfs|devtmpfs" | head -20

line "inode 使用率"
df -i 2>/dev/null | head -20

line "失败的 systemd 单元"
if have systemctl; then systemctl --failed --no-pager 2>/dev/null || echo "（不可用）"; else echo "（无 systemd）"; fi

line "最近的错误日志（journalctl -p err）"
if have journalctl; then journalctl -p err -n 30 --no-pager 2>/dev/null || echo "（需要权限或日志不可用）"; else echo "（无 journalctl）"; fi

line "内核日志中的硬件与 OOM 线索"
if have dmesg; then dmesg -T 2>/dev/null | grep -i -E "error|fail|oom|killed process|i/o" | tail -20 || true; else echo "（dmesg 需要 root）"; fi

line "CPU 占用最高的进程"
ps aux --sort=-%cpu 2>/dev/null | head -8

line "内存占用最高的进程"
ps aux --sort=-%rss 2>/dev/null | head -8

line "监听端口"
if have ss; then ss -tlnp 2>/dev/null | head -20; elif have netstat; then netstat -tlnp 2>/dev/null | head -20; else echo "（无 ss/netstat）"; fi

line "文件句柄与进程数上限"
ulimit -n 2>/dev/null; echo "已用句柄："$(cat /proc/sys/fs/file-nr 2>/dev/null)

line "时间同步状态"
if have timedatectl; then timedatectl 2>/dev/null | head -6; else date; fi

echo
echo "==== 采集结束 ===="
