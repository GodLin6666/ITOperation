package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 诊断记录（一次诊断的全过程留痕），对应表 diagnose_record */
@Data
@Entity
@Table(name = "diagnose_record")
public class DiagnoseRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ticketId;
    private String templatesJson;
    private String keywords;
    private String hitsJson;
    private String answerJson;
    private String rawAnswer;
    private String modelName;
    private Integer firstTokenMs;
    private Integer costMs;
    private Integer adopted;
    private LocalDateTime createTime;
}
