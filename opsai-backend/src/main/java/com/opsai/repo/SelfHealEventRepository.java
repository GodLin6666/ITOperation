package com.opsai.repo;

import com.opsai.entity.SelfHealEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 自愈事件仓库 */
public interface SelfHealEventRepository extends JpaRepository<SelfHealEvent, Long> {

    /** 全部事件，最新的在前 */
    List<SelfHealEvent> findTop200ByOrderByDetectTimeDesc();

    /** 某条规则最近的事件（用于去重，避免同一问题反复刷屏） */
    List<SelfHealEvent> findTop5ByRuleKeyOrderByDetectTimeDesc(String ruleKey);

    /** 按状态查 */
    List<SelfHealEvent> findByStatusOrderByDetectTimeDesc(String status);

    long countByStatus(String status);
}
