package com.opsai.selfheal;

import com.opsai.entity.KbDocument;
import com.opsai.service.KbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动作：重建知识库向量索引。
 * <p>
 * 适用场景：检索命中率明显下降、换过嵌入模型、或者知识库刚批量导入过。
 * 重建只是重新切块 + 重算向量，**不会删除或修改知识正文**，所以风险较低。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReindexKbAction implements SelfHealAction {

    private final KbService kbService;

    @Override
    public String key() {
        return "REINDEX_KB";
    }

    @Override
    public String title() {
        return "重建知识库向量索引";
    }

    @Override
    public String risk() {
        return "LOW";
    }

    @Override
    public String plan() {
        List<KbDocument> docs = kbService.listPublished();
        int chunks = docs.stream().mapToInt(d -> d.getChunkCount() == null ? 0 : d.getChunkCount()).sum();
        long minutes = Math.max(1, docs.size() * 3L / 60);
        return String.format(
                "对全部已发布知识重新分块并重算向量。\n"
                        + "涉及：%d 篇文档（当前共 %d 个向量块）\n"
                        + "不会修改知识正文，只重建 kb_chunk 里的分块和向量。\n"
                        + "预计耗时：约 %d 分钟（每篇要调用一次嵌入模型）。",
                docs.size(), chunks, minutes);
    }

    @Override
    public String run() throws Exception {
        List<KbDocument> docs = kbService.listPublished();
        int ok = 0;
        int fail = 0;
        int chunks = 0;
        for (KbDocument d : docs) {
            try {
                KbDocument r = kbService.reindex(d.getId());
                chunks += r.getChunkCount() == null ? 0 : r.getChunkCount();
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[自愈] 重建失败 doc#{} {}: {}", d.getId(), d.getTitle(), e.getMessage());
            }
        }
        String msg = String.format("重建完成：成功 %d 篇，失败 %d 篇，共生成 %d 个向量块", ok, fail, chunks);
        log.info("[自愈] 重建知识库索引: {}", msg);
        return msg;
    }

    @Override
    public Recheck recheck() {
        List<KbDocument> docs = kbService.listPublished();
        if (docs.isEmpty()) {
            return new Recheck(false, "知识库为空，重建没有意义，需要补充知识");
        }
        long empty = docs.stream()
                .filter(d -> d.getChunkCount() == null || d.getChunkCount() <= 0)
                .count();
        int total = docs.stream().mapToInt(d -> d.getChunkCount() == null ? 0 : d.getChunkCount()).sum();
        if (empty == 0) {
            return new Recheck(true, "全部 " + docs.size() + " 篇文档均已建立索引，共 " + total + " 个向量块");
        }
        return new Recheck(false, empty + " 篇文档没有生成向量块");
    }
}
