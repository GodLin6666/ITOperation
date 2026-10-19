package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 诊断记录（一次诊断的全过程留痕），对应表 diagnose_record。
 * <p>
 * 几个 JSON 字段和模型原始输出都可能很长，显式声明 TEXT，
 * 否则桌面版用 ddl-auto 自动建表时会变成 VARCHAR(255) 导致插入失败。
 */
@Data
@Entity
@Table(name = "diagnose_record")
public class DiagnoseRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ticketId;

    @Column(columnDefinition = "TEXT")
    private String templatesJson;

    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Column(columnDefinition = "TEXT")
    private String hitsJson;

    @Column(columnDefinition = "TEXT")
    private String answerJson;

    @Column(columnDefinition = "TEXT")
    private String rawAnswer;

    private String modelName;
    private Integer firstTokenMs;
    private Integer costMs;
    private Integer adopted;
    private LocalDateTime createTime;
}
