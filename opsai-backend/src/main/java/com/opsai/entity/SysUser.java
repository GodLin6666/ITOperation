package com.opsai.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（登录和角色权限），对应表 sys_user。
 * <p>
 * auth_source 区分两种账号：
 * <ul>
 *   <li>LOCAL —— 本系统自己维护密码（BCrypt），内置 admin/engineer/viewer 属于这类，域控故障时可应急登录</li>
 *   <li>LDAP  —— 企业域账号，密码由域控校验，本地只存一条用于留痕的记录（password 字段填不可用的随机串）</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "sys_user")
public class SysUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;   // BCrypt 密文；LDAP 账号存随机串，永远匹配不上
    private String realName;
    private String role;       // admin / engineer / viewer
    private Integer status;

    /** LOCAL / LDAP */
    private String authSource;

    private LocalDateTime createTime;
}
