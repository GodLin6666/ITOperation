package com.opsai.selfheal;

import com.opsai.client.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 动作：预热本地模型。
 * <p>
 * 解决的是「闲置后第一次提问特别慢」——Ollama 会在空闲几分钟后把模型从显存卸载，
 * 下次请求要重新加载（实测冷启动首字 1.5~10 秒，热态只要 87~235 毫秒）。
 * <p>
 * 这个动作只发一次极短请求，不改任何数据，属于风险最低的自愈动作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmupModelAction implements SelfHealAction {

    private final OllamaClient ollama;

    @Override
    public String key() {
        return "WARMUP_MODEL";
    }

    @Override
    public String title() {
        return "预热本地模型";
    }

    @Override
    public String risk() {
        return "LOW";
    }

    @Override
    public String plan() {
        return "向本地 Ollama 各发一次极短请求，把对话模型和向量模型加载进显存并保活 30 分钟。\n"
                + "影响范围：只占用显存，不改动任何数据文件。\n"
                + "预期效果：把冷启动的首字延迟（1.5~10 秒）降到 200 毫秒以内。";
    }

    @Override
    public String run() throws Exception {
        long t1 = System.currentTimeMillis();
        ollama.chat("你是一个助手。", "只回复 ok 两个字。");
        long chatMs = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        ollama.embed("预热");
        long embedMs = System.currentTimeMillis() - t2;

        String msg = String.format("对话模型加载完成（%d ms），向量模型加载完成（%d ms）", chatMs, embedMs);
        log.info("[自愈] 预热完成: {}", msg);
        return msg;
    }

    @Override
    public Recheck recheck() {
        try {
            Map<String, Object> h = ollama.health();
            boolean chat = Boolean.TRUE.equals(h.get("chatModelReady"));
            boolean embed = Boolean.TRUE.equals(h.get("embedModelReady"));
            if (chat && embed) {
                return new Recheck(true, "两个模型都已就绪，可正常提供诊断服务");
            }
            return new Recheck(false, "模型仍未就绪（对话=" + chat + "，向量=" + embed + "）");
        } catch (Exception e) {
            return new Recheck(false, "复检失败，Ollama 可能已不可达：" + e.getMessage());
        }
    }
}
