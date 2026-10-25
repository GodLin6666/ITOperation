package com.opsai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsai.client.OllamaClient;
import com.opsai.config.OpsAiProps;
import com.opsai.entity.KbDocument;
import com.opsai.entity.SelfHealEvent;
import com.opsai.repo.SelfHealEventRepository;
import com.opsai.selfheal.SelfHealAction;
import com.opsai.selfheal.SelfHealActionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * 自愈服务：巡检 → 诊断 → 提案 → 人工确认 → 执行 → 复检。
 *
 * <p><b>安全设计（这是本功能最重要的部分）：</b>
 * <ol>
 *   <li>巡检只采集指标、生成事件，<b>不做任何改动</b>。</li>
 *   <li>处置动作只能从 {@link SelfHealActionRegistry} 的白名单里选，
 *       大模型的输出<b>永远不会</b>变成被执行的动作。</li>
 *   <li>事件必须经过人工确认（{@link #execute}），巡检永远不会自己调用执行。</li>
 *   <li>执行前先跑 dry-run（{@link SelfHealAction#plan()}），把"将要做什么"写进事件，
 *       管理员看过才点确认。</li>
 *   <li>执行后立刻复检（{@link SelfHealAction#recheck()}），并写操作日志。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfHealService {

    /** 判定阈值 */
    private static final double DISK_HIGH_PCT = 85.0;
    private static final double MEMORY_HIGH_PCT = 90.0;
    private static final long UPLOAD_DIR_BIG_BYTES = 200L * 1024 * 1024;

    /** 同一规则在这个时间窗内不重复建事件，避免刷屏 */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(30);

    private final SelfHealEventRepository repo;
    private final SelfHealActionRegistry registry;
    private final DiagnoseService diagnoseService;
    private final KbService kbService;
    private final OllamaClient ollama;
    private final OpsAiProps props;
    private final OpLogService opLog;

    // ============================================================
    // 一、巡检
    // ============================================================

    /** 定时巡检。间隔可通过 opsai.selfheal.scan-interval-ms 调整，默认 2 分钟。 */
    @Scheduled(fixedDelayString = "${opsai.selfheal.scan-interval-ms:120000}",
            initialDelayString = "${opsai.selfheal.scan-initial-delay-ms:60000}")
    public void scheduledScan() {
        try {
            List<SelfHealEvent> created = scan();
            if (!created.isEmpty()) {
                log.info("[自愈] 巡检发现 {} 个新问题: {}", created.size(),
                        created.stream().map(SelfHealEvent::getRuleKey).toList());
            }
        } catch (Exception e) {
            log.warn("[自愈] 巡检异常: {}", e.getMessage());
        }
    }

    /**
     * 执行一次巡检，返回本次新建的事件。
     * 只采集指标 + 建事件 + 自动挂上白名单动作，不做任何处置。
     */
    public List<SelfHealEvent> scan() {
        List<SelfHealEvent> created = new ArrayList<>();
        Map<String, Object> m = metrics();

        // ---- 规则 1：磁盘使用率过高 ----
        @SuppressWarnings("unchecked")
        Map<String, Object> disk = (Map<String, Object>) m.get("disk");
        double diskPct = num(disk.get("usedPct"));
        if (diskPct > DISK_HIGH_PCT) {
            created.add(maybeCreate("DISK_HIGH", "磁盘空间不足",
                    diskPct >= 95 ? "HIGH" : "MEDIUM",
                    "disk_usage", diskPct, DISK_HIGH_PCT,
                    String.format("磁盘使用率已达 %.1f%%（阈值 %.0f%%），可用空间仅剩 %s。",
                            diskPct, DISK_HIGH_PCT, disk.get("usableHuman")),
                    "CLEAN_UPLOAD_CACHE"));
        }

        // ---- 规则 2：JVM 内存占用过高 ----
        @SuppressWarnings("unchecked")
        Map<String, Object> mem = (Map<String, Object>) m.get("memory");
        double memPct = num(mem.get("usedPct"));
        if (memPct > MEMORY_HIGH_PCT) {
            created.add(maybeCreate("MEMORY_HIGH", "服务内存占用过高",
                    "MEDIUM", "memory_usage", memPct, MEMORY_HIGH_PCT,
                    String.format("后端 JVM 堆内存使用率 %.1f%%（阈值 %.0f%%）。", memPct, MEMORY_HIGH_PCT),
                    null));
        }

        // ---- 规则 3：Ollama 不可达 ----
        @SuppressWarnings("unchecked")
        Map<String, Object> ol = (Map<String, Object>) m.get("ollama");
        boolean ollamaUp = Boolean.TRUE.equals(ol.get("up"));
        if (!ollamaUp) {
            created.add(maybeCreate("OLLAMA_DOWN", "本地模型服务不可达",
                    "HIGH", "ollama_up", 0, 1,
                    "无法连接本地 Ollama（" + props.getOllama().getBaseUrl() + "）。"
                            + "诊断功能将完全不可用，需要人工确认 Ollama 是否在运行。",
                    null));
        } else {
            // ---- 规则 4：模型未加载（冷启动）----
            boolean chatReady = Boolean.TRUE.equals(ol.get("chatModelReady"));
            boolean embedReady = Boolean.TRUE.equals(ol.get("embedModelReady"));
            if (!chatReady || !embedReady) {
                created.add(maybeCreate("OLLAMA_COLD", "本地模型未加载",
                        "LOW", "chat_model_ready", 0, 1,
                        String.format("Ollama 在线，但模型未加载进显存（对话=%s，向量=%s），"
                                        + "此时第一次诊断会额外等待数秒。",
                                chatReady ? "已加载" : "未加载", embedReady ? "已加载" : "未加载"),
                        "WARMUP_MODEL"));
            }
        }

        // ---- 规则 5：上传目录过大 ----
        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) m.get("upload");
        long upBytes = ((Number) up.get("bytes")).longValue();
        if (upBytes > UPLOAD_DIR_BIG_BYTES) {
            created.add(maybeCreate("UPLOAD_DIR_BIG", "上传文件占用空间过大",
                    "LOW", "upload_dir_bytes", upBytes, (double) UPLOAD_DIR_BIG_BYTES,
                    String.format("上传目录已占用 %s，其中超过 7 天的文件可以安全清理。",
                            up.get("sizeHuman")),
                    "CLEAN_UPLOAD_CACHE"));
        }

        // ---- 规则 6：知识库为空 ----
        @SuppressWarnings("unchecked")
        Map<String, Object> kb = (Map<String, Object>) m.get("kb");
        long docCount = ((Number) kb.get("documents")).longValue();
        long emptyDocs = ((Number) kb.get("emptyDocs")).longValue();
        if (docCount == 0) {
            created.add(maybeCreate("KB_EMPTY", "知识库为空",
                    "HIGH", "kb_documents", 0, 1,
                    "知识库中没有任何已发布的知识，检索阶段拿不到任何依据，诊断质量会非常差。"
                            + "需要人工补充知识（可在知识库管理页批量导入种子数据）。",
                    null));
        } else if (emptyDocs > 0) {
            created.add(maybeCreate("KB_NO_VECTOR", "部分知识缺少向量索引",
                    "MEDIUM", "kb_empty_docs", emptyDocs, 0,
                    String.format("%d 篇文档没有生成向量块，检索时永远不会被命中。", emptyDocs),
                    "REINDEX_KB"));
        }

        created.removeIf(Objects::isNull);
        return created;
    }

    /** 建事件（带去重）。若已存在同规则的未闭合事件，则跳过。 */
    private SelfHealEvent maybeCreate(String ruleKey, String title, String severity,
                                      String metricName, double value, double threshold,
                                      String desc, String actionKey) {
        LocalDateTime since = LocalDateTime.now().minus(DEDUP_WINDOW);
        boolean open = repo.findTop5ByRuleKeyOrderByDetectTimeDesc(ruleKey).stream()
                .anyMatch(e -> e.getDetectTime() != null
                        && e.getDetectTime().isAfter(since)
                        && !"VERIFIED".equals(e.getStatus())
                        && !"FAILED".equals(e.getStatus())
                        && !"IGNORED".equals(e.getStatus()));
        if (open) return null;

        SelfHealEvent e = new SelfHealEvent();
        e.setRuleKey(ruleKey);
        e.setTitle(title);
        e.setSeverity(severity);
        e.setMetricName(metricName);
        e.setMetricValue(value);
        e.setThreshold(threshold);
        e.setStatus("DETECTED");
        e.setDiagnosis(desc);
        e.setDetectTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());

        // 能自动匹配到白名单动作的，直接预置好 dry-run 预览，管理员点开就能看到要做什么
        if (actionKey != null && registry.has(actionKey)) {
            SelfHealAction a = registry.get(actionKey);
            e.setActionKey(actionKey);
            try {
                e.setActionPlan(a.plan());
            } catch (Exception ex) {
                e.setActionPlan("预览生成失败：" + ex.getMessage());
            }
            e.setStatus("PROPOSED");
        }
        return repo.save(e);
    }

    // ============================================================
    // 二、诊断（按需，调用 RAG）
    // ============================================================

    /** 对某条事件跑一次 RAG 诊断，把模型给出的原因和建议记下来（不执行任何动作）。 */
    public SelfHealEvent diagnose(Long id) throws Exception {
        SelfHealEvent e = must(id);
        String phenomenon = e.getTitle() + "：" + (e.getDiagnosis() == null ? "" : e.getDiagnosis());
        // 自愈巡检针对的是本机服务器，按 Linux 平台检索
        DiagnoseService.DiagnoseResponse r = diagnoseService.diagnose(
                "自愈巡检-" + e.getRuleKey(), "server", "system", phenomenon, null, "LINUX");

        JsonNode answer = r.answer();
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("summary", answer.path("summary").asText());
        List<Map<String, String>> causes = new ArrayList<>();
        int n = 0;
        for (JsonNode c : answer.path("causes")) {
            if (n++ >= 3) break;
            causes.add(Map.of(
                    "cause", c.path("cause").asText(),
                    "fix", c.path("fix").asText()));
        }
        brief.put("causes", causes);
        brief.put("ticketId", r.ticketId());

        e.setDiagnosis(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(brief));
        if ("DETECTED".equals(e.getStatus())) e.setStatus("DIAGNOSED");
        e.setUpdateTime(LocalDateTime.now());
        return repo.save(e);
    }

    // ============================================================
    // 三、提案 / 执行 / 忽略
    // ============================================================

    /** 重新生成 dry-run 预览（管理员执行前看到的就是这段） */
    public SelfHealEvent propose(Long id) {
        SelfHealEvent e = must(id);
        String key = e.getActionKey();
        if (!registry.has(key)) {
            throw new IllegalStateException("该事件没有可用的白名单处置动作，需要人工处理");
        }
        SelfHealAction a = registry.get(key);
        e.setActionPlan(a.plan());
        if ("DETECTED".equals(e.getStatus()) || "DIAGNOSED".equals(e.getStatus())) {
            e.setStatus("PROPOSED");
        }
        e.setUpdateTime(LocalDateTime.now());
        return repo.save(e);
    }

    /**
     * 执行处置动作。**必须由管理员在界面上确认后调用。**
     * 执行完立刻复检，根据复检结果把状态置为 VERIFIED 或 FAILED。
     */
    public SelfHealEvent execute(Long id, String operator) {
        SelfHealEvent e = must(id);

        if (!"PROPOSED".equals(e.getStatus()) && !"DIAGNOSED".equals(e.getStatus())) {
            throw new IllegalStateException("当前状态为 " + e.getStatus() + "，不能执行");
        }
        SelfHealAction action = registry.get(e.getActionKey());
        if (action == null) {
            // 安全兜底：action_key 不在白名单里，一律拒绝
            throw new IllegalStateException("动作「" + e.getActionKey() + "」不在白名单中，已拒绝执行");
        }

        long start = System.currentTimeMillis();
        String result;
        try {
            result = action.run();
        } catch (Exception ex) {
            e.setStatus("FAILED");
            e.setActionResult("执行异常：" + ex.getMessage());
            e.setOperator(operator);
            e.setExecuteTime(LocalDateTime.now());
            e.setUpdateTime(LocalDateTime.now());
            repo.save(e);
            opLog.log(operator, "自愈执行失败", e.getTitle() + "｜" + ex.getMessage(), null,
                    (int) (System.currentTimeMillis() - start));
            return e;
        }
        long cost = System.currentTimeMillis() - start;

        // 立刻复检：不能只看"执行成功"，要看指标有没有真的恢复
        SelfHealAction.Recheck rc = action.recheck();
        e.setStatus(rc.healthy() ? "VERIFIED" : "FAILED");
        e.setActionResult(result + "\n\n复检：" + (rc.healthy() ? "✅ " : "❌ ") + rc.message());
        e.setOperator(operator);
        e.setExecuteTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        repo.save(e);

        opLog.log(operator, "自愈执行", e.getTitle() + "｜动作 " + action.key()
                + "｜结果 " + (rc.healthy() ? "已恢复" : "未恢复"), null, (int) cost);
        return e;
    }

    /** 忽略某条事件（人工判断不需要处理） */
    public SelfHealEvent ignore(Long id, String operator) {
        SelfHealEvent e = must(id);
        e.setStatus("IGNORED");
        e.setOperator(operator);
        e.setUpdateTime(LocalDateTime.now());
        repo.save(e);
        opLog.log(operator, "自愈忽略", e.getTitle(), null, null);
        return e;
    }

    // ============================================================
    // 四、查询
    // ============================================================

    public List<SelfHealEvent> list() {
        return repo.findTop200ByOrderByDetectTimeDesc();
    }

    public SelfHealEvent detail(Long id) {
        return must(id);
    }

    /** 当前健康指标（前端概览用） */
    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("disk", diskMetric());
        out.put("memory", memoryMetric());
        out.put("ollama", ollamaMetric());
        out.put("kb", kbMetric());
        out.put("upload", uploadMetric());

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of("DETECTED", "DIAGNOSED", "PROPOSED", "EXECUTED", "VERIFIED", "FAILED", "IGNORED")) {
            long c = repo.countByStatus(s);
            if (c > 0) byStatus.put(s, c);
        }
        out.put("eventsByStatus", byStatus);
        out.put("openEvents", repo.countByStatus("DETECTED") + repo.countByStatus("DIAGNOSED")
                + repo.countByStatus("PROPOSED"));
        return out;
    }

    private Map<String, Object> diskMetric() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Path dir = Paths.get(props.getStorage().getUploadDir()).toAbsolutePath().normalize();
            if (!Files.exists(dir)) Files.createDirectories(dir);
            var store = Files.getFileStore(dir);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            double usedPct = total <= 0 ? 0 : (total - usable) * 100.0 / total;
            m.put("usedPct", round1(usedPct));
            m.put("totalHuman", human(total));
            m.put("usableHuman", human(usable));
            m.put("threshold", DISK_HIGH_PCT);
        } catch (Exception e) {
            m.put("usedPct", 0.0);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> memoryMetric() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usedPct", round1(max <= 0 ? 0 : used * 100.0 / max));
        m.put("usedHuman", human(used));
        m.put("maxHuman", human(max));
        m.put("threshold", MEMORY_HIGH_PCT);
        return m;
    }

    private Map<String, Object> ollamaMetric() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Map<String, Object> h = ollama.health();
            m.put("up", true);
            m.put("models", h.get("models"));
            m.put("chatModelReady", h.get("chatModelReady"));
            m.put("embedModelReady", h.get("embedModelReady"));
        } catch (Exception e) {
            m.put("up", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> kbMetric() {
        List<KbDocument> docs = kbService.listPublished();
        int chunks = docs.stream().mapToInt(d -> d.getChunkCount() == null ? 0 : d.getChunkCount()).sum();
        long empty = docs.stream().filter(d -> d.getChunkCount() == null || d.getChunkCount() <= 0).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documents", docs.size());
        m.put("chunks", chunks);
        m.put("emptyDocs", empty);
        return m;
    }

    private Map<String, Object> uploadMetric() {
        Map<String, Object> m = new LinkedHashMap<>();
        Path dir = Paths.get(props.getStorage().getUploadDir()).toAbsolutePath().normalize();
        long bytes = 0;
        long files = 0;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> list = walk.filter(Files::isRegularFile).toList();
                files = list.size();
                for (Path p : list) {
                    try {
                        bytes += Files.size(p);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        m.put("files", files);
        m.put("bytes", bytes);
        m.put("sizeHuman", human(bytes));
        return m;
    }

    // ---------------- 工具 ----------------

    private SelfHealEvent must(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("自愈事件不存在: " + id));
    }

    private double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
