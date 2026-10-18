package com.opsai.controller;

import com.opsai.client.OllamaClient;
import com.opsai.service.DiagnoseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 核心接口：健康检查 + 一次诊断 + 追问。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OpsController {

    private final DiagnoseService diagnoseService;
    private final OllamaClient ollamaClient;

    /** 健康检查：顺带报告 Ollama 和两个模型是否就绪 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("time", LocalDateTime.now().toString());
        try {
            out.put("llm", ollamaClient.health());
        } catch (Exception e) {
            out.put("status", "degraded");
            out.put("llm", Map.of("ollama", "down", "error", e.getMessage()));
        }
        return out;
    }

    /** 核心接口：一次诊断 */
    @PostMapping("/diagnose")
    public DiagnoseService.DiagnoseResponse diagnose(@RequestBody DiagnoseRequest req) throws Exception {
        return diagnoseService.diagnose(req.title(), req.deviceType(),
                req.faultCategory(), req.phenomenon(), req.logText());
    }

    /** 追问：在已有诊断结果上继续对话 */
    @PostMapping("/diagnose/followup")
    public DiagnoseService.FollowUpResponse followUp(@RequestBody FollowUpRequest req) throws Exception {
        if (req.ticketId() == null) throw new IllegalArgumentException("缺少 ticketId");
        if (req.question() == null || req.question().isBlank()) throw new IllegalArgumentException("追问内容不能为空");
        return diagnoseService.followUp(req.ticketId(), req.question());
    }

    public record DiagnoseRequest(String title, String deviceType, String faultCategory,
                                  String phenomenon, String logText) {
    }

    public record FollowUpRequest(Long ticketId, String question) {
    }
}
