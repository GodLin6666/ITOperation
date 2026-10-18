package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自愈事件，对应表 self_heal_event。
 * <p>
 * 一条事件的完整生命周期：
 * <pre>
 *   DETECTED   巡检发现异常（例如磁盘使用率 92%）
 *   DIAGNOSED  已调用 RAG 诊断，知道大概是什么原因
 *   PROPOSED   已从白名单里选出一个处置动作，并生成 dry-run 预览
 *   EXECUTED   管理员确认后执行完毕
 *   VERIFIED   执行后复检通过，指标恢复正常
 *   FAILED     执行后复检没恢复，需要人工介入
 *   IGNORED    人工判断不用处理
 * </pre>
 * 注意：从 PROPOSED 到 EXECUTED 这一步**必须是人工点的**，系统不会自己跨过去。
 */
@Data
@Entity
@Table(name = "self_heal_event")
public class SelfHealEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 触发规则，如 DISK_HIGH / OLLAMA_DOWN / KB_EMPTY */
    private String ruleKey;

    private String title;

    /** LOW / MEDIUM / HIGH */
    private String severity;

    /** 指标名，便于前端展示，如 disk_usage */
    private String metricName;

    private Double metricValue;

    private Double threshold;

    /** 见类注释里的状态流转 */
    private String status;

    /** 诊断结果（JSON 字符串） */
    @Column(columnDefinition = "TEXT")
    private String diagnosis;

    /** 选中的白名单动作 key */
    private String actionKey;

    /** dry-run 预览：执行前明确告诉人"将要做什么" */
    @Column(columnDefinition = "TEXT")
    private String actionPlan;

    /** 执行结果 */
    @Column(columnDefinition = "TEXT")
    private String actionResult;

    /** 审批 / 执行人 */
    private String operator;

    private LocalDateTime detectTime;

    private LocalDateTime executeTime;

    private LocalDateTime updateTime;
}
