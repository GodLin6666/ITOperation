package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识分块 + 向量，对应表 kb_chunk。RAG 的核心。 */
@Data
@Entity
@Table(name = "kb_chunk")
public class KbChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long docId;
    private Integer chunkNo;
    private String content;
    private String embedding;      // JSON 数组字符串，1024 维
    private Integer dim;
    private String embedModel;
    private LocalDateTime createTime;
}
