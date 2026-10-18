package com.opsai.rag;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第三段流程：提示词拼装。
 * 这是整个系统「能不能少胡说」的关键 —— 提示词写得越死板，输出越稳。
 */
@Component
public class PromptBuilder {

    /** 系统提示词：约束模型的角色、依据来源、输出格式 */
    public static final String SYSTEM = """
            你是一名资深 IT 运维工程师，负责根据用户提供的故障现象、日志和知识库资料，给出可执行的排查建议。
            必须遵守：
            1. 只依据【参考资料】和通用运维常识作答，不得编造资料中不存在的命令、路径、参数或结论。
            2. 每一条原因都要写明依据，并在 evidence 里用 [资料N] 的形式引用。
            3. 如果参考资料不足以判断，要明确写出「资料不足」，并给出继续采集哪些信息的方向。
            4. 排查步骤要具体到命令或操作，按执行先后顺序排列。
            5. 只输出 JSON，不要输出任何解释文字，不要用 Markdown 代码块包裹。
            JSON 结构：
            {"summary":"一句话结论",
             "causes":[{"cause":"可能原因","confidence":0.0,"evidence":"依据说明，引用[资料N]","checkSteps":["步骤1","步骤2"],"fix":"处置建议"}],
             "needMoreInfo":["还需要补充的信息"]}
            """;

    /** 追问场景的系统提示词：在已有结论上继续深挖 */
    public static final String FOLLOWUP_SYSTEM = """
            你是一名资深 IT 运维工程师。用户已经拿到一份诊断结论，现在追问细节。
            必须遵守：
            1. 只依据【参考资料】和【已有诊断结论】作答，不得编造不存在的命令、路径或参数。
            2. 回答要直接、具体，给出可以照着敲的命令或操作。
            3. 如果资料不足，明确说明「资料不足」，并说明还需要采集什么信息。
            4. 用简洁的中文回答，可以用短列表，不要输出 JSON。
            """;

    public String build(String preprocessSummary, List<Retriever.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户故障信息】\n").append(preprocessSummary).append("\n\n");
        sb.append("【参考资料】\n");
        appendHits(sb, hits);
        sb.append("请严格按 JSON 结构输出诊断结果。");
        return sb.toString();
    }

    /** 追问用：把原始问题、已有结论、参考资料一起给模型 */
    public String buildFollowUp(String question, String previousAnswer, List<Retriever.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【已有诊断结论】\n").append(previousAnswer == null ? "（无）" : previousAnswer).append("\n\n");
        sb.append("【参考资料】\n");
        appendHits(sb, hits);
        sb.append("【用户的追问】\n").append(question).append("\n\n");
        sb.append("请针对用户的追问给出具体、可执行的回答。");
        return sb.toString();
    }

    private void appendHits(StringBuilder sb, List<Retriever.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            sb.append("（本次没有检索到相关资料，请依据通用运维经验作答，并提醒用户补充信息）\n\n");
            return;
        }
        int no = 1;
        for (Retriever.Hit hit : hits) {
            sb.append("[资料").append(no++).append("] 标题：").append(hit.title())
                    .append("｜设备类型：").append(hit.deviceType())
                    .append("｜故障类别：").append(hit.category())
                    .append("｜相似度：").append(String.format("%.2f", hit.score())).append("\n")
                    .append(hit.content()).append("\n\n");
        }
    }
}
