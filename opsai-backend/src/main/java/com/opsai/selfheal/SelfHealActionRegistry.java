package com.opsai.selfheal;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 白名单动作注册表。
 * <p>
 * 启动时把所有 {@link SelfHealAction} 实现收集进来。任何不在这个表里的 key
 * 都无法执行——即使数据库里的 action_key 被人手工改成别的值，执行时也会被拒绝。
 */
@Component
public class SelfHealActionRegistry {

    private final Map<String, SelfHealAction> actions = new LinkedHashMap<>();

    public SelfHealActionRegistry(List<SelfHealAction> list) {
        list.forEach(a -> actions.put(a.key(), a));
    }

    public Collection<SelfHealAction> all() {
        return actions.values();
    }

    /** 按 key 取动作；不存在返回 null（调用方必须判空，这是安全兜底） */
    public SelfHealAction get(String key) {
        return key == null ? null : actions.get(key);
    }

    public boolean has(String key) {
        return key != null && actions.containsKey(key);
    }
}
