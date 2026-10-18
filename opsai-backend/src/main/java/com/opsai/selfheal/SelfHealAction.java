package com.opsai.selfheal;

/**
 * 自愈动作。**这是自愈功能的唯一执行入口，也是安全边界所在。**
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>白名单制</b>：系统只会执行实现了本接口的动作，大模型生成的任何命令都不会被执行。
 *       模型的输出只用来"解释原因"和"给出建议"，不参与决定执行什么。</li>
 *   <li><b>先预览后执行</b>：{@link #plan()} 只描述要做什么，不产生任何副作用，
 *       前端把它展示给管理员看过、点确认后才会调用 {@link #run()}。</li>
 *   <li><b>执行后复检</b>：{@link #recheck()} 在动作跑完后重新采集指标，
 *       确认问题真的解决了，而不是"看起来执行成功"。</li>
 * </ul>
 */
public interface SelfHealAction {

    /** 唯一标识，会存进 self_heal_event.action_key */
    String key();

    /** 给人看的名字 */
    String title();

    /** 风险等级：LOW / MEDIUM / HIGH */
    String risk();

    /** 预览：说明将要做什么、影响范围多大。**不得产生副作用。** */
    String plan();

    /** 真正执行。返回值是一段人可读的结果描述，会存进 action_result。 */
    String run() throws Exception;

    /** 执行后复检，确认指标是否已恢复 */
    Recheck recheck();

    /** 复检结果 */
    record Recheck(boolean healthy, String message) {
    }
}
