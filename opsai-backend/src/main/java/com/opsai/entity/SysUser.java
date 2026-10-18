package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户（登录和角色权限），对应表 sys_user */
@Data
@Entity
@Table(name = "sys_user")
public class SysUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;   // BCrypt 密文
    private String realName;
    private String role;       // admin / engineer / viewer
    private Integer status;
    private LocalDateTime createTime;
}
