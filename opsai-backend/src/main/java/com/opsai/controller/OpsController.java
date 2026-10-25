package com.opsai.controller;

import com.opsai.client.OllamaClient;
import com.opsai.config.OpsAiProps;
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
    private final OpsAiProps props;

    /**
     * 健康检查：报告 Ollama 和两个模型是否就绪，以及是否启用了域认证。
     * 这个接口不走登录校验，登录页据此决定要不要显示「用域账号登录」的提示。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("time", LocalDateTime.now().toString());
        // 企业域环境：前端登录页据此提示可以用域账号
        out.put("ldapEnabled", props.getLdap().isEnabled());
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
                req.faultCategory(), req.phenomenon(), req.logText(), req.platform());
    }

    /** 追问：在已有诊断结果上继续对话 */
    @PostMapping("/diagnose/followup")
    public DiagnoseService.FollowUpResponse followUp(@RequestBody FollowUpRequest req) throws Exception {
        if (req.ticketId() == null) throw new IllegalArgumentException("缺少 ticketId");
        if (req.question() == null || req.question().isBlank()) throw new IllegalArgumentException("追问内容不能为空");
        return diagnoseService.followUp(req.ticketId(), req.question());
    }

    public record DiagnoseRequest(String title, String deviceType, String faultCategory,
                                  String phenomenon, String logText, String platform) {
    }

    public record FollowUpRequest(Long ticketId, String question) {
    }
}
