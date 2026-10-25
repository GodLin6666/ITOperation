package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识条目（历史故障案例 / 排查手册 / 处置方案），对应表 kb_document */
@Data
@Entity
@Table(name = "kb_document")
public class KbDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String deviceType;
    private String faultCategory;
    private String docType;

    /** 适用平台：WINDOWS / LINUX / ANY（通用），检索时按平台过滤 */
    @Column(length = 16)
    private String platform;

    /** 知识正文，可能上千字，必须 TEXT（否则桌面版自动建表会变成 VARCHAR(255)） */
    @Column(columnDefinition = "TEXT")
    private String content;

    private String source;
    private Integer status;
    private Integer version;
    private Integer chunkCount;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
