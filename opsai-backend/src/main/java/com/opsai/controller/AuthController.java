package com.opsai.controller;

import com.opsai.service.Session;
import com.opsai.service.AuthService;
import com.opsai.service.Session;
import com.opsai.service.OpLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 登录相关接口 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final OpLogService opLog;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        if (req.username() == null || req.password() == null) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        try {
            Map<String, Object> r = authService.login(req.username(), req.password());
            opLog.log(req.username(), "登录",
                    "角色 " + r.get("role") + "｜姓名 " + r.get("realName"), http.getRemoteAddr(), null);
            return r;
        } catch (RuntimeException e) {
            // 登录失败也要记：管理端能看出是不是有人在试密码
            opLog.log(req.username(), "登录失败", e.getMessage(), http.getRemoteAddr(), null);
            throw e;
        }
    }

    /** 校验当前 token 还有效吗（前端刷新页面时用） */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        String token = request.getHeader("X-Token");
        Session s = authService.verify(token);
        if (s == null) throw new IllegalStateException("未登录或登录已过期");
        return Map.of("username", s.username(), "realName", s.realName(), "role", s.role());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String token = request.getHeader("X-Token");
        Session s = authService.verify(token);
        authService.logout(token);
        if (s != null) opLog.log(s.username(), "退出登录", null, request.getRemoteAddr(), null);
        return Map.of("ok", true);
    }

    public record LoginRequest(String username, String password) {
    }
}
