package com.opsai.controller;

import com.opsai.entity.KbDocument;
import com.opsai.service.KbService;
import com.opsai.service.OpLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库管理接口：新增（自动分块+向量化）、列表、审核、删除、重建索引。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;
    private final OpLogService opLog;

    /** 录入一篇知识 */
    @PostMapping("/document")
    public KbDocument addDocument(@RequestBody KbDocument doc, HttpServletRequest http) {
        if (doc.getTitle() == null || doc.getTitle().isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (doc.getContent() == null || doc.getContent().isBlank()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        KbDocument saved = kbService.saveDocument(doc);
        opLog.log(who(http), "新增知识",
                "《" + saved.getTitle() + "》切成 " + saved.getChunkCount() + " 块", http.getRemoteAddr(), null);
        return saved;
    }

    /** 全部知识（含待审核），知识库管理页用 */
    @GetMapping("/documents")
    public List<KbDocument> listDocuments() {
        return kbService.listAll();
    }

    /** 只要已发布的，检索用的就是这批 */
    @GetMapping("/documents/published")
    public List<KbDocument> listPublished() {
        return kbService.listPublished();
    }

    /** 审核：status 传 1 发布 / 2 驳回 */
    @PostMapping("/document/{id}/audit")
    public KbDocument audit(@PathVariable Long id, @RequestParam Integer status, HttpServletRequest http) {
        KbDocument d = kbService.audit(id, status);
        opLog.log(who(http), status == 1 ? "发布知识" : "驳回知识",
                "#" + id + "《" + d.getTitle() + "》", http.getRemoteAddr(), null);
        return d;
    }

    /** 重新分块 + 重新算向量 */
    @PostMapping("/document/{id}/reindex")
    public KbDocument reindex(@PathVariable Long id, HttpServletRequest http) {
        KbDocument d = kbService.reindex(id);
        opLog.log(who(http), "重建向量", "#" + id + "《" + d.getTitle() + "》" + d.getChunkCount() + " 块",
                http.getRemoteAddr(), null);
        return d;
    }

    @DeleteMapping("/document/{id}")
    public void delete(@PathVariable Long id, HttpServletRequest http) {
        kbService.delete(id);
        opLog.log(who(http), "删除知识", "#" + id, http.getRemoteAddr(), null);
    }

    private String who(HttpServletRequest http) {
        Object u = http.getAttribute("username");
        return u == null ? "unknown" : String.valueOf(u);
    }
}
