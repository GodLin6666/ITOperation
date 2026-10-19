package com.opsai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsai.entity.KbDocument;
import com.opsai.service.KbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面版（exe）启动引导。
 *
 * <p>只在 {@code opsai.standalone=true} 时生效，做两件事：
 * <ol>
 *   <li>服务起来后自动打开浏览器，用户双击 exe 就能直接看到界面；</li>
 *   <li>知识库为空时在后台自动导入内置种子（45 条），首次启动不用手动灌数据。</li>
 * </ol>
 *
 * <p>注意：导入知识要给每条算向量，需要 Ollama 在线。如果 Ollama 没起来，
 * 这里只记一条警告、不会启动失败——用户之后可以在知识库页面点"导入种子"重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "opsai.standalone", havingValue = "true")
public class StandaloneBootstrap {

    private static final int PORT = 8080;

    private final KbService kbService;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        openBrowser();
        // 导入要调嵌入模型，比较慢，放后台线程，不阻塞启动
        Thread t = new Thread(this::seedKbIfEmpty, "standalone-seed");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- 打开浏览器 ----------------

    private void openBrowser() {
        String url = "http://localhost:" + PORT;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                log.info("[桌面版] 已打开浏览器: {}", url);
                return;
            }
        } catch (Exception e) {
            log.warn("[桌面版] Desktop.browse 失败，改用命令行: {}", e.getMessage());
        }
        // 兜底：Windows 上用 rundll32 打开默认浏览器
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            log.info("[桌面版] 已通过 rundll32 打开浏览器: {}", url);
        } catch (Exception e) {
            log.warn("[桌面版] 打开浏览器失败，请手动访问 {}（{}）", url, e.getMessage());
        }
    }

    // ---------------- 首次导入知识库 ----------------

    private void seedKbIfEmpty() {
        try {
            List<KbDocument> existing = kbService.listAll();
            if (!existing.isEmpty()) {
                log.info("[桌面版] 知识库已有 {} 条，跳过自动导入", existing.size());
                return;
            }

            List<JsonNode> seeds = loadSeeds();
            if (seeds.isEmpty()) {
                log.warn("[桌面版] 没找到内置种子数据（classpath:/knowledge/*.json），跳过自动导入");
                return;
            }

            log.info("[桌面版] 知识库为空，开始导入 {} 条内置知识（首次启动约需 2~5 分钟，需要 Ollama 在线）", seeds.size());
            int ok = 0;
            int fail = 0;
            for (int i = 0; i < seeds.size(); i++) {
                JsonNode n = seeds.get(i);
                try {
                    KbDocument d = new KbDocument();
                    d.setTitle(n.path("title").asText());
                    d.setDeviceType(n.path("deviceType").asText("app"));
                    d.setFaultCategory(n.path("faultCategory").asText("system"));
                    d.setDocType(n.path("docType").asText("manual"));
                    d.setSource(n.path("source").asText("内置种子"));
                    d.setContent(n.path("content").asText());
                    kbService.saveDocument(d);
                    ok++;
                    log.info("[桌面版] 导入 {}/{}: {}", i + 1, seeds.size(), d.getTitle());
                } catch (Exception e) {
                    fail++;
                    log.warn("[桌面版] 第 {} 条导入失败（{}）: {}", i + 1, n.path("title").asText(), e.getMessage());
                    // 嵌入模型不可用时后续都会失败，直接停下，避免刷屏
                    if (fail >= 3) {
                        log.warn("[桌面版] 连续失败，停止自动导入。请确认 Ollama 已启动且模型已加载。");
                        break;
                    }
                }
            }
            log.info("[桌面版] 知识库导入结束：成功 {} 条，失败 {} 条", ok, fail);
        } catch (Exception e) {
            log.warn("[桌面版] 自动导入知识库异常: {}", e.getMessage());
        }
    }

    /** 读取打进 jar 里的种子文件 */
    private List<JsonNode> loadSeeds() {
        List<JsonNode> out = new ArrayList<>();
        for (String f : List.of("knowledge/kb-seed.json", "knowledge/kb-desktop.json")) {
            ClassPathResource res = new ClassPathResource(f);
            if (!res.exists()) continue;
            try (InputStream in = res.getInputStream()) {
                JsonNode arr = objectMapper.readTree(in);
                if (arr.isArray()) {
                    arr.forEach(out::add);
                    log.info("[桌面版] 读取种子文件 {}：{} 条", f, arr.size());
                }
            } catch (Exception e) {
                log.warn("[桌面版] 读取 {} 失败: {}", f, e.getMessage());
            }
        }
        return out;
    }
}
