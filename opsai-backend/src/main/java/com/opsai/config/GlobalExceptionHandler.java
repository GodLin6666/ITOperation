package com.opsai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理：把后端的异常变成前端能直接显示的 JSON，
 * 而不是一大坨 Java 堆栈（新手调试时最容易懵的地方）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * 请求方法不对（比如用 GET 调 POST 接口）应该返回 405，而不是 500。
     * <p>
     * 这一点是验收时发现的：原来所有异常都落到兜底的 500 分支，
     * 于是"用错了 HTTP 方法"这种客户端错误也被报成"服务器内部错误"，
     * 排查时会误以为是后端崩了。顺带把支持的方法也告诉调用方。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null
                ? "" : "，该接口支持 " + e.getSupportedHttpMethods();
        return build(HttpStatus.METHOD_NOT_ALLOWED,
                "请求方法 " + e.getMethod() + " 不被支持" + supported);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("接口处理失败", e);
        String msg = e.getMessage();
        if (msg != null && (msg.contains("Connection refused") || msg.contains("11434"))) {
            msg = "连接不上本地 Ollama（http://localhost:11434），请确认 Ollama 正在运行。原始信息：" + msg;
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
