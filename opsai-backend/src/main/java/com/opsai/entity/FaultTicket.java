package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 故障单（用户提交的问题），对应表 fault_ticket。
 * <p>
 * phenomenon / logText / metricJson 都可能很长：
 * 现象描述是用户输入，日志动辄几十 KB，指标是 JSON。
 * 显式声明 TEXT，否则桌面版 ddl-auto 自动建表时会变成 VARCHAR(255)。
 */
@Data
@Entity
@Table(name = "fault_ticket")
public class FaultTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String ticketNo;
    private String title;
    private String deviceType;
    private String faultCategory;

    /** 本次故障所属平台：WINDOWS / LINUX / ANY，诊断时按它过滤知识 */
    @Column(length = 16)
    private String platform;

    @Column(columnDefinition = "TEXT")
    private String phenomenon;

    @Column(columnDefinition = "TEXT")
    private String logText;

    private String logFile;

    @Column(columnDefinition = "TEXT")
    private String metricJson;

    private Integer status;
    private Long createBy;
    private LocalDateTime createTime;
}
