package com.opsai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版会话存储（默认）。
 *
 * <p>只适合**单个后端实例**。多开的话必须换成 {@link RedisTokenStore}，
 * 否则用户会被负载均衡踢掉线。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "opsai.auth.store", havingValue = "memory", matchIfMissing = true)
public class MemoryTokenStore implements TokenStore {

    private final Map<String, Session> tokens = new ConcurrentHashMap<>();

    public MemoryTokenStore() {
        log.info("[会话] 使用内存存储（单实例部署）。多开后端请把 opsai.auth.store 改成 redis");
    }

    @Override
    public void put(String token, Session session, long ttlSeconds) {
        tokens.put(token, session);
    }

    @Override
    public Session get(String token) {
        if (token == null || token.isBlank()) return null;
        Session s = tokens.get(token);
        if (s == null) return null;
        if (s.expired()) {
            tokens.remove(token);
            return null;
        }
        return s;
    }

    @Override
    public void remove(String token) {
        if (token != null) tokens.remove(token);
    }

    @Override
    public String kind() {
        return "memory";
    }
}
