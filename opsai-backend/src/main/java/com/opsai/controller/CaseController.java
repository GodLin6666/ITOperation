package com.opsai.controller;

import com.opsai.service.DiagnoseService;
import com.opsai.service.ExportService;
import com.opsai.service.OpLogService;
import com.opsai.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 案例与处理单：历史故障单列表、详情、标记采纳、导出处理单。
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class CaseController {

    private final StatsService statsService;
    private final DiagnoseService diagnoseService;
    private final ExportService exportService;
    private final OpLogService opLog;

    /** 案例列表 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return statsService.ticketList();
    }

    /** 案例详情：故障单 + 全部诊断记录 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return statsService.ticketDetail(id);
    }

    /** 标记建议是否被采纳 */
    @PostMapping("/{id}/adopt")
    public Map<String, Object> adopt(@PathVariable Long id,
                                     @RequestParam(defaultValue = "true") boolean adopted,
                                     HttpServletRequest http) {
        diagnoseService.markAdopted(id, adopted);
        String who = http.getAttribute("username") == null ? "unknown" : String.valueOf(http.getAttribute("username"));
        opLog.log(who, adopted ? "采纳建议" : "取消采纳", "故障单 #" + id, http.getRemoteAddr(), null);
        return Map.of("ticketId", id, "adopted", adopted);
    }

    /** 导出处理单为 Word (.docx) */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id, HttpServletRequest http) throws Exception {
        Map<String, Object> detail = statsService.ticketDetail(id);
        byte[] docx = exportService.buildTicketDocx(detail);

        String who = http.getAttribute("username") == null ? "unknown" : String.valueOf(http.getAttribute("username"));
        opLog.log(who, "导出处理单", "故障单 #" + id, http.getRemoteAddr(), null);

        String filename = URLEncoder.encode("故障处理单", StandardCharsets.UTF_8).replace("+", "%20") + "-" + id + ".docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ticket-" + id + ".docx\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(docx);
    }
}
