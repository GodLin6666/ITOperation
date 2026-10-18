package com.opsai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsai.service.DiagnoseService;
import com.opsai.service.OpLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读用户（普通用户）的「极简问答」接口。
 * <p>
 * 和 /api/diagnose 的区别：那边是给运维人员看的完整诊断（模板、关键词、命中片段、耗时全给），
 * 这边面向「在自己电脑上遇到问题、想赶紧解决」的普通用户，只返回三样东西：
 * <ul>
 *   <li>一句结论</li>
 *   <li>最多 3 条可能原因（每条带排查步骤和处置建议）</li>
 *   <li>参考了哪几篇资料（只给标题和相似度，不给全文）</li>
 * </ul>
 * 原始日志、向量、模板、耗时这些细节只留在后台，管理端能查到，普通用户看不到。
 */
@RestController
@RequestMapping("/api/assist")
@RequiredArgsConstructor
public class AssistController {

    private final DiagnoseService diagnoseService;
    private final OpLogService opLog;

    /** 用户提问（可以只发文字，也可以带上截图 OCR 出来的文字） */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody AskRequest req, HttpServletRequest http) throws Exception {
        String question = req.question() == null ? "" : req.question().trim();
        String imageText = req.imageText() == null ? "" : req.imageText().trim();

        if (question.isEmpty() && imageText.isEmpty()) {
            throw new IllegalArgumentException("请先描述一下遇到的问题，或者贴一张截图");
        }

        String username = String.valueOf(http.getAttribute("username"));
        String title = question.isEmpty()
                ? "截图提问"
                : (question.length() > 40 ? question.substring(0, 40) : question);

        long start = System.currentTimeMillis();
        // 截图 OCR 出来的文字当作“日志”喂给诊断流程；用户的问题作为“现象描述”
        DiagnoseService.DiagnoseResponse r = diagnoseService.diagnose(
                title, "app", "app", question, imageText.isEmpty() ? null : imageText);
        long cost = System.currentTimeMillis() - start;

        // ---- 精简输出：只保留普通用户需要看的字段 ----
        List<Map<String, Object>> causes = new ArrayList<>();
        JsonNode arr = r.answer().path("causes");
        int n = 0;
        for (JsonNode c : arr) {
            if (n++ >= 3) break;                       // 最多 3 条，避免信息过载
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cause", c.path("cause").asText());
            m.put("confidence", c.path("confidence").asDouble());
            List<String> steps = new ArrayList<>();
            for (JsonNode s : c.path("checkSteps")) {
                steps.add(s.asText());
                if (steps.size() >= 6) break;          // 每步最多 6 条
            }
            m.put("steps", steps);
            m.put("fix", c.path("fix").asText());
            causes.add(m);
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        r.hits().stream().limit(3).forEach(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", h.title());
            m.put("score", Math.round(h.score() * 1000) / 1000.0);
            sources.add(m);
        });

        opLog.log(username, "询问助手",
                "问：" + (question.isEmpty() ? "（仅截图）" : question)
                        + "｜命中 " + r.hits().size() + " 篇"
                        + "｜故障单 #" + r.ticketId()
                        + (req.imagePath() == null || req.imagePath().isBlank()
                        ? "" : "｜截图 " + req.imagePath()),
                http.getRemoteAddr(), (int) cost);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conclusion", r.answer().path("summary").asText());
        if (out.get("conclusion") == null || String.valueOf(out.get("conclusion")).isBlank()) {
            out.put("conclusion", "已根据你的描述给出排查建议，详见下面。");
        }
        out.put("causes", causes);
        out.put("sources", sources);
        out.put("needMoreInfo", toList(r.answer().path("needMoreInfo")));
        out.put("ticketId", r.ticketId());
        out.put("costMs", r.costMs());
        return out;
    }

    private List<String> toList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode x : node) {
                list.add(x.asText());
                if (list.size() >= 3) break;
            }
        }
        return list;
    }

    public record AskRequest(String question, String imageText, String title, String imagePath) {
    }
}
