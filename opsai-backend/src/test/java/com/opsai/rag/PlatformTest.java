package com.opsai.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台识别与过滤规则的单元测试。
 * 这几条规则决定了「Windows 桌面问题会不会检索到 Linux 命令」，改动前先跑一遍。
 */
class PlatformTest {

    @Test
    void normalize_acceptsCommonWritings() {
        assertEquals(Platform.WINDOWS, Platform.normalize("windows"));
        assertEquals(Platform.WINDOWS, Platform.normalize("Win"));
        assertEquals(Platform.WINDOWS, Platform.normalize("desktop"));
        assertEquals(Platform.LINUX, Platform.normalize("linux"));
        assertEquals(Platform.LINUX, Platform.normalize("unix"));
        assertEquals(Platform.ANY, Platform.normalize(null));
        assertEquals(Platform.ANY, Platform.normalize(""));
        assertEquals(Platform.ANY, Platform.normalize("unknown"));
    }

    @Test
    void detect_linuxFromJournalAndDisk() {
        String log = "systemd[1]: nginx.service: Failed with result 'exit-code'\n"
                + "df -h 显示 /var/log 分区 100%";
        assertEquals(Platform.LINUX, Platform.detect("nginx 起不来", log));
    }

    @Test
    void detect_windowsFromEventIds() {
        String log = "事件 ID 7000 Print Spooler 服务启动失败\n"
                + "Windows 事件查看器：系统日志 ID 1001 蓝屏";
        assertEquals(Platform.WINDOWS, Platform.detect("打印机打印不了", log));
    }

    @Test
    void detect_returnsAnyWhenNoSignal() {
        assertEquals(Platform.ANY, Platform.detect("接口报错", "HTTP 500"));
        assertEquals(Platform.ANY, Platform.detect(null, null));
    }

    @Test
    void detect_tieFallsBackToAny() {
        // 一边一个特征词：journalctl（Linux）+ 蓝屏（Windows），打平按通用处理
        String log = "journalctl 报错，机器蓝屏";
        assertEquals(Platform.ANY, Platform.detect("", log));
    }

    @Test
    void matches_keepsCommonKnowledgeVisible() {
        assertTrue(Platform.matches("ANY", Platform.WINDOWS));
        assertTrue(Platform.matches(null, Platform.LINUX));
        assertTrue(Platform.matches("", Platform.LINUX));
        assertTrue(Platform.matches(Platform.LINUX, Platform.LINUX));
        assertFalse(Platform.matches(Platform.LINUX, Platform.WINDOWS));
    }

    @Test
    void matches_withoutRequestedPlatformDoesNotFilter() {
        assertTrue(Platform.matches(Platform.WINDOWS, null));
        assertTrue(Platform.matches(Platform.LINUX, Platform.ANY));
    }
}
