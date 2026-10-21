package com.opsai.service;

/**
 * 一个登录会话。
 * <p>
 * 抽成独立的顶层类（原来是 AuthService 的内部 record），
 * 是因为现在它要能被两种存储共用：内存 Map 和 Redis。
 */
public record Session(String username, String realName, String role, long expireAt) {

    public boolean expired() {
        return System.currentTimeMillis() > expireAt;
    }
}
