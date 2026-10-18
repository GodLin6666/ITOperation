package com.opsai.service;

import com.opsai.config.OpsAiProps;
import com.opsai.entity.SysUser;
import com.opsai.repo.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录与 token 管理。
 * 本项目是单机系统，token 直接放在内存里就够了，不引入 Redis/JWT 这些额外组件。
 * （项目需求里提到 Redis，等真的需要多实例时再换，换的时候只改这一个类。）
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 一个登录会话 */
    public record Session(String username, String realName, String role, long expireAt) {
        public boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private final SysUserRepository userRepo;
    private final OpsAiProps props;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Session> tokens = new ConcurrentHashMap<>();

    /** 登录成功返回 token，失败抛异常 */
    public Map<String, Object> login(String username, String password) {
        SysUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new IllegalStateException("该账号已被禁用");
        }
        if (!encoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        long expireAt = System.currentTimeMillis() + props.getAuth().getTokenHours() * 3600_000L;
        tokens.put(token, new Session(user.getUsername(),
                user.getRealName() == null ? user.getUsername() : user.getRealName(),
                user.getRole(), expireAt));

        return Map.of(
                "token", token,
                "username", user.getUsername(),
                "realName", user.getRealName() == null ? user.getUsername() : user.getRealName(),
                "role", user.getRole(),
                "expireAt", Instant.ofEpochMilli(expireAt).toString());
    }

    /** 校验 token，无效返回 null */
    public Session verify(String token) {
        if (token == null || token.isBlank()) return null;
        Session s = tokens.get(token);
        if (s == null) return null;
        if (s.expired()) {
            tokens.remove(token);
            return null;
        }
        return s;
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }

    /** 生成 BCrypt 密文（初始化管理员账号、加新用户时用） */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public BCryptPasswordEncoder encoder() {
        return encoder;
    }
}
