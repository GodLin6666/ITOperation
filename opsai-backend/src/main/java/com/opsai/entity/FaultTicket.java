package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 故障单（用户提交的问题），对应表 fault_ticket */
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
    private String phenomenon;
    private String logText;
    private String logFile;
    private String metricJson;
    private Integer status;
    private Long createBy;
    private LocalDateTime createTime;
}
