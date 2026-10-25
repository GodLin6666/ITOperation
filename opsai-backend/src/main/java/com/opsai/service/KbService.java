package com.opsai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsai.client.OllamaClient;
import com.opsai.config.OpsAiProps;
import com.opsai.entity.KbChunk;
import com.opsai.entity.KbDocument;
import com.opsai.repo.KbChunkRepository;
import com.opsai.repo.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库服务：录入一篇手册/案例，自动切块、自动算向量、自动入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private final KbDocumentRepository docRepo;
    private final KbChunkRepository chunkRepo;
    private final OllamaClient ollama;
    private final OpsAiProps props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 录入知识：保存正文 -> 切块 -> 逐块算向量 -> 入库 */
    @Transactional
    public KbDocument saveDocument(KbDocument doc) {
        // 注意：这几张表的字段在 SQL 里是 NOT NULL + DEFAULT，
        // 但 Hibernate 插入时会显式带上 null，反而覆盖掉数据库默认值而报错，
        // 所以这里必须逐个补默认值。
        if (doc.getStatus() == null) doc.setStatus(1);
        if (doc.getVersion() == null) doc.setVersion(1);
        if (doc.getChunkCount() == null) doc.setChunkCount(0);
        if (doc.getDocType() == null || doc.getDocType().isBlank()) doc.setDocType("manual");
        // 没写平台的按通用处理，避免老数据被平台过滤掉
        doc.setPlatform(com.opsai.rag.Platform.normalize(doc.getPlatform()));
        LocalDateTime now = LocalDateTime.now();
        if (doc.getCreateTime() == null) doc.setCreateTime(now);
        doc.setUpdateTime(now);
        doc = docRepo.save(doc);

        // 重新录入时先清掉旧的分块，避免重复
        chunkRepo.deleteByDocId(doc.getId());
        chunkRepo.flush();

        List<String> blocks = split(doc.getContent());
        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i);
            KbChunk chunk = new KbChunk();
            chunk.setDocId(doc.getId());
            chunk.setChunkNo(i + 1);
            chunk.setContent(block);
            chunk.setEmbedding(toJson(ollama.embed(block)));
            chunk.setDim(1024);
            chunk.setEmbedModel(props.getOllama().getEmbedModel());
            chunk.setCreateTime(now);
            chunkRepo.save(chunk);
        }
        doc.setChunkCount(blocks.size());
        log.info("知识入库完成：id={} 标题={} 分块数={}", doc.getId(), doc.getTitle(), blocks.size());
        return docRepo.save(doc);
    }

    /** 简单滑窗切块：每块 400 字，相邻重叠 80 字 */
    public List<String> split(String text) {
        List<String> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) return blocks;
        int size = props.getRag().getChunkSize();
        int overlap = props.getRag().getChunkOverlap();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            blocks.add(text.substring(start, end));
            if (end >= text.length()) break;
            start = end - overlap;
        }
        return blocks;
    }

    public List<KbDocument> listPublished() {
        return docRepo.findByStatusOrderByUpdateTimeDesc(1);
    }

    public List<KbDocument> listAll() {
        return docRepo.findAllByOrderByUpdateTimeDesc();
    }

    /** 审核：0 待审核 1 已发布 2 已驳回 */
    public KbDocument audit(Long id, Integer status) {
        KbDocument doc = docRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识条目不存在：" + id));
        doc.setStatus(status);
        return docRepo.save(doc);
    }

    @Transactional
    public void delete(Long id) {
        chunkRepo.deleteByDocId(id);
        docRepo.deleteById(id);
    }

    /** 重新向量化（改了切块参数或者换了嵌入模型时用） */
    @Transactional
    public KbDocument reindex(Long id) {
        KbDocument doc = docRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识条目不存在：" + id));
        return saveDocument(doc);
    }

    private String toJson(double[] vec) {
        try {
            return mapper.writeValueAsString(vec);
        } catch (Exception e) {
            throw new IllegalStateException("向量序列化失败", e);
        }
    }
}
