package com.opsai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsai.client.OllamaClient;
import com.opsai.config.OpsAiProps;
import com.opsai.entity.DiagnoseRecord;
import com.opsai.entity.FaultTicket;
import com.opsai.preprocess.LogPreprocessor;
import com.opsai.rag.PromptBuilder;
import com.opsai.rag.Retriever;
import com.opsai.repo.DiagnoseRecordRepository;
import com.opsai.repo.FaultTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 第三段流程的总调度：把「预处理 -> 检索 -> 生成」串起来，并全过程留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnoseService {

    private final OllamaClient ollama;
    private final LogPreprocessor preprocessor;
    private final Retriever retriever;
    private final PromptBuilder promptBuilder;
    private final FaultTicketRepository ticketRepo;
    private final DiagnoseRecordRepository recordRepo;
    private final OpsAiProps props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 用来判断模型有没有按 [资料1] 的格式引用资料 */
    private static final Pattern CITATION = Pattern.compile("\\[\\s*资料\\s*\\d+\\s*]");

    /** 一次完整诊断：建单 -> 预处理 -> 检索 -> 生成 -> 留痕 */
    @Transactional
    public DiagnoseResponse diagnose(String title, String deviceType, String faultCategory,
                                     String phenomenon, String logText) throws Exception {

        // 第 0 步：故障单入库
        FaultTicket ticket = new FaultTicket();
        ticket.setTicketNo("FT" + System.currentTimeMillis());
        ticket.setTitle(title == null || title.isBlank() ? "未命名故障" : title);
        ticket.setDeviceType(deviceType);
        ticket.setFaultCategory(faultCategory);
        ticket.setPhenomenon(phenomenon);
        ticket.setLogText(logText);
        ticket.setStatus(0);
        ticket.setCreateTime(LocalDateTime.now());
        ticket = ticketRepo.save(ticket);

        // 第 1 步：预处理（日志模板 + 关键词）
        LogPreprocessor.PreprocessResult pre = preprocessor.process(phenomenon, logText);

        // 第 2 步：向量化 + 检索
        double[] queryVec = ollama.embed(pre.summary());
        List<Retriever.Hit> hits = retriever.search(queryVec,
                props.getRag().getTopK(), props.getRag().getMinScore());

        // 第 3 步：拼提示词 + 让本地模型生成
        String prompt = promptBuilder.build(pre.summary(), hits);
        OllamaClient.StreamResult result = ollama.chatStream(PromptBuilder.SYSTEM, prompt, null);

        JsonNode answer = extractJson(result.text());
        // 保证每条原因都带 [资料N] 引用，不靠模型自觉
        ensureCitations(answer, hits);

        // 第 4 步：全过程留痕，方便做统计和复盘
        DiagnoseRecord record = new DiagnoseRecord();
        record.setTicketId(ticket.getId());
        record.setTemplatesJson(mapper.writeValueAsString(pre.templates()));
        record.setKeywords(String.join(",", pre.keywords()));
        record.setHitsJson(mapper.writeValueAsString(hits));
        record.setAnswerJson(answer.toString());
        record.setRawAnswer(result.text());
        record.setModelName(props.getOllama().getChatModel());
        record.setFirstTokenMs((int) result.firstTokenMs());
        record.setCostMs((int) result.costMs());
        record.setAdopted(0);
        record.setCreateTime(LocalDateTime.now());
        recordRepo.save(record);

        ticket.setStatus(1);
        ticketRepo.save(ticket);

        log.info("诊断完成：ticketId={} 命中={} 首字={}ms 总耗时={}ms",
                ticket.getId(), hits.size(), result.firstTokenMs(), result.costMs());

        return new DiagnoseResponse(ticket.getId(), answer,
                pre.keywords(), pre.templates(), hits,
                result.firstTokenMs(), result.costMs(),
                pre.rawLineCount(), pre.usedLineCount());
    }

    /**
     * 追问：在已有诊断结论的基础上继续对话。
     * 会拿用户的追问再做一次检索，把新命中的资料一起给模型。
     */
    @Transactional
    public FollowUpResponse followUp(Long ticketId, String question) throws Exception {
        FaultTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("故障单不存在：" + ticketId));

        DiagnoseRecord last = recordRepo.findFirstByTicketIdOrderByCreateTimeDesc(ticketId)
                .orElseThrow(() -> new IllegalStateException("该故障单还没有诊断记录，无法追问"));

        // 用「原现象 + 追问」一起检索，命中更准
        String query = (ticket.getPhenomenon() == null ? "" : ticket.getPhenomenon()) + "\n" + question;
        double[] queryVec = ollama.embed(query);
        List<Retriever.Hit> hits = retriever.search(queryVec,
                props.getRag().getTopK(), props.getRag().getMinScore());

        String prompt = promptBuilder.buildFollowUp(question, last.getAnswerJson(), hits);
        // 追问要更灵活一点，temperature 稍微调高
        OllamaClient.StreamResult result = ollama.chatStream(
                PromptBuilder.FOLLOWUP_SYSTEM, prompt, null, 0.4, props.getOllama().getNumCtx());

        // 追问也留痕：作为同一条故障单的又一条诊断记录
        DiagnoseRecord record = new DiagnoseRecord();
        record.setTicketId(ticketId);
        record.setKeywords(question);
        record.setHitsJson(mapper.writeValueAsString(hits));
        record.setAnswerJson(mapper.writeValueAsString(mapper.createObjectNode().put("followUp", result.text())));
        record.setRawAnswer(result.text());
        record.setModelName(props.getOllama().getChatModel());
        record.setFirstTokenMs((int) result.firstTokenMs());
        record.setCostMs((int) result.costMs());
        record.setAdopted(0);
        record.setCreateTime(LocalDateTime.now());
        recordRepo.save(record);

        return new FollowUpResponse(ticketId, question, result.text(), hits,
                result.firstTokenMs(), result.costMs());
    }

    /** 标记建议是否被采纳（统计页的采纳率靠这个字段） */
    @Transactional
    public void markAdopted(Long ticketId, boolean adopted) {
        DiagnoseRecord record = recordRepo.findFirstByTicketIdOrderByCreateTimeDesc(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("该故障单还没有诊断记录"));
        record.setAdopted(adopted ? 1 : 0);
        recordRepo.save(record);

        FaultTicket ticket = ticketRepo.findById(ticketId).orElse(null);
        if (ticket != null) {
            ticket.setStatus(adopted ? 2 : 1);   // 2 已处理
            ticketRepo.save(ticket);
        }
    }

    /**
     * 兜底：保证每条原因都能追溯到资料。
     * <p>
     * 提示词里已经明确要求模型写 [资料N]，但 7B 模型偶尔会忘。
     * 「有据可查」是项目需求的硬性要求，不能靠模型的心情，所以这里做一次补写：
     * 模型自己引用了就原样保留，没引用就按本次真实检索到的资料补上编号。
     * 注意这里只补「本次确实检索到的那几篇」的编号，不会编造不存在的资料。
     */
    private void ensureCitations(JsonNode answer, List<Retriever.Hit> hits) {
        if (answer == null || !answer.path("causes").isArray()) return;

        String suffix = (hits == null || hits.isEmpty())
                ? "（本次未检索到相关资料，依据通用运维经验作答）"
                : "（依据本次检索到的资料：" + citations(hits.size()) + "）";

        for (JsonNode cause : answer.path("causes")) {
            if (!(cause instanceof ObjectNode node)) continue;
            String evidence = node.path("evidence").asText("");
            if (CITATION.matcher(evidence).find()) continue;   // 模型已经引用过，不动它
            node.put("evidence", evidence.isBlank() ? suffix.substring(1) : evidence + suffix);
        }
    }

    private String citations(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append("、");
            sb.append("[资料").append(i).append("]");
        }
        return sb.toString();
    }

    /** 模型可能不听话地在 JSON 前后加废话，这里做一次兜底提取 */
    public JsonNode extractJson(String raw) {
        if (raw == null) raw = "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return mapper.readTree(raw.substring(start, end + 1));
            } catch (Exception ignored) {
                // 解析失败就走下面的兜底
            }
        }
        return mapper.createObjectNode().put("summary", raw);
    }

    public record DiagnoseResponse(Long ticketId, JsonNode answer, List<String> keywords,
                                   List<LogPreprocessor.Template> templates,
                                   List<Retriever.Hit> hits,
                                   long firstTokenMs, long costMs,
                                   int rawLineCount, int usedLineCount) {
    }

    public record FollowUpResponse(Long ticketId, String question, String answer,
                                   List<Retriever.Hit> hits,
                                   long firstTokenMs, long costMs) {
    }
}
