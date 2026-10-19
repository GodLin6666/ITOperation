package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识分块 + 向量，对应表 kb_chunk。RAG 的核心。
 * <p>
 * content 和 embedding 必须显式声明成 TEXT：
 * 不写的话 Hibernate 会按默认长度建成 VARCHAR(255)，
 * 而一个 400 字的分块和 1024 维向量的 JSON 都远超这个长度。
 * （MySQL 那边 init.sql 里本来就是 TEXT，桌面版 H2 靠 ddl-auto 自动建表，所以这里必须写清楚。）
 */
@Data
@Entity
@Table(name = "kb_chunk")
public class KbChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long docId;
    private Integer chunkNo;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** JSON 数组字符串，1024 维向量，约 20KB */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    private Integer dim;
    private String embedModel;
    private LocalDateTime createTime;
}
