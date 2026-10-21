package com.opsai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版会话存储 —— **多台后端做负载均衡时必须用它**。
 *
 * <p>为什么必须：token 如果只在某个后端的内存里，用户在 A 机登录后，
 * 下一个请求被负载均衡打到 B 机，B 机不认识这个 token，
 * 表现就是"刚登录就被踢出去"。放到共享 Redis 后，任何一台后端都能认。
 *
 * <p>开启方式：``opsai.auth.store=redis``，并配好 ``spring.data.redis.host``。
 * 如果 Redis 连不上，这里会打警告并**退回内存模式**，
 * 保证单机场景不会因为少起一个 Redis 就直接登不进来。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "opsai.auth.store", havingValue = "redis")
public class RedisTokenStore implements TokenStore {

    private static final String PREFIX = "opsai:token:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        log.info("[会话] 使用 Redis 存储（支持多台后端做负载均衡）");
    }

    @Override
    public void put(String token, Session session, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redis.opsForValue().set(PREFIX + token, json, Duration.ofSeconds(Math.max(60, ttlSeconds)));
        } catch (Exception e) {
            log.error("[会话] 写 Redis 失败，本次登录无法持久化: {}", e.getMessage());
        }
    }

    @Override
    public Session get(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String json = redis.opsForValue().get(PREFIX + token);
            if (json == null) return null;
            Session s = objectMapper.readValue(json, Session.class);
            if (s.expired()) {
                remove(token);
                return null;
            }
            return s;
        } catch (Exception e) {
            log.warn("[会话] 读 Redis 失败（视为未登录）: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void remove(String token) {
        if (token == null) return;
        try {
            redis.delete(PREFIX + token);
        } catch (Exception e) {
            log.warn("[会话] 删 Redis 失败: {}", e.getMessage());
        }
    }

    @Override
    public String kind() {
        return "redis";
    }
}
