package com.opsai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsai.entity.KbChunk;
import com.opsai.entity.KbDocument;
import com.opsai.repo.KbChunkRepository;
import com.opsai.repo.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 第二段流程：向量检索。
 * <p>
 * 做法是「全量加载 + 内存算余弦相似度」。几百上千块知识只要几十毫秒，
 * 少一个向量数据库组件就少一堆报错。等知识量上万再换成 Chroma，
 * 到时候只需要替换这一个类。
 */
@Component
@RequiredArgsConstructor
public class Retriever {

    private final KbChunkRepository chunkRepo;
    private final KbDocumentRepository docRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 一次检索命中 */
    public record Hit(Long chunkId, Long docId, String title, String deviceType,
                      String category, String content, double score) {
    }

    /** 用查询向量在知识库里找最相似的 topK 块 */
    public List<Hit> search(double[] queryVec, int topK, double minScore) {
        List<KbChunk> chunks = chunkRepo.findAll();
        if (chunks.isEmpty()) return List.of();

        List<Long> docIds = chunks.stream().map(KbChunk::getDocId).distinct().toList();
        Map<Long, KbDocument> docs = docRepo.findAllById(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, d -> d));

        List<Hit> hits = new ArrayList<>();
        for (KbChunk chunk : chunks) {
            KbDocument doc = docs.get(chunk.getDocId());
            if (doc == null) continue;
            if (doc.getStatus() == null || doc.getStatus() != 1) continue;   // 只检索已发布的知识
            double score = cosine(queryVec, parse(chunk.getEmbedding()));
            if (score >= minScore) {
                hits.add(new Hit(chunk.getId(), doc.getId(), doc.getTitle(),
                        doc.getDeviceType(), doc.getFaultCategory(), chunk.getContent(), score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.stream().limit(topK).toList();
    }

    /** 余弦相似度：只看方向不看长度，结果在 -1 到 1 之间，越大越相似 */
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0d;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0d;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private double[] parse(String json) {
        if (json == null || json.isBlank()) return new double[0];
        try {
            return mapper.readValue(json, double[].class);
        } catch (Exception e) {
            return new double[0];
        }
    }
}
