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
    private String content;
    private String source;
    private Integer status;
    private Integer version;
    private Integer chunkCount;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
