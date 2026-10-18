package com.opsai.config;

import com.opsai.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web 层配置：跨域 + 登录拦截 + 角色权限。
 * 权限设计（项目需求要求的 admin/engineer/viewer 三级）：
 *   admin    —— 全部权限
 *   engineer —— 诊断、录知识；不能审核/删除知识
 *   viewer   —— 只读
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthService authService;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 本地开发时前端跑在 5173，后端在 8080，需要放行跨域
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 让 JSON 响应带上 charset=UTF-8。
     * <p>
     * 默认情况下 Spring 返回的 Content-Type 是 application/json 不带字符集，
     * 浏览器会按 UTF-8 处理所以没问题，但 Windows PowerShell 5.1 的
     * Invoke-RestMethod 会退化成 ISO-8859-1，导致中文全部变成乱码。
     * 验收脚本的验收步骤就是用 PowerShell 调的，所以这里显式声明字符集。
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(authService))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/auth/login");
    }

    /** 登录 + 角色校验拦截器 */
    static class AuthInterceptor implements HandlerInterceptor {

        private final AuthService authService;

        AuthInterceptor(AuthService authService) {
            this.authService = authService;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

            String token = request.getHeader("X-Token");
            if (token == null) token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) token = token.substring(7);

            AuthService.Session session = authService.verify(token);
            if (session == null) {
                write(response, 401, "未登录或登录已过期，请重新登录");
                return false;
            }

            String uri = request.getRequestURI();
            String method = request.getMethod();
            String role = session.role();
            boolean isViewer = "viewer".equals(role);

            // ---------- 只读用户（普通用户）的权限边界 ----------
            // 定位：在自己在电脑上遇到问题，问一句、贴张图、拿个建议就走。
            // 所以：放行"提问/贴图/退出登录"，其余写操作一律禁止；
            //       同时禁止读取知识库、案例、统计、日志这些管理数据（不只是前端藏起来，接口也拦住）。
            if (isViewer) {
                boolean viewerMayWrite = uri.equals("/api/auth/logout")
                        || uri.startsWith("/api/assist/")
                        || uri.equals("/api/upload/image");
                if (!"GET".equalsIgnoreCase(method) && !viewerMayWrite) {
                    write(response, 403, "当前账号只能提问，不能执行该操作");
                    return false;
                }
                boolean adminData = uri.startsWith("/api/kb")
                        || uri.startsWith("/api/tickets")
                        || uri.startsWith("/api/stats")
                        || uri.startsWith("/api/logs")
                        || uri.startsWith("/api/diagnose");
                if (adminData) {
                    write(response, 403, "当前账号无权查看运维管理数据");
                    return false;
                }
            }

            // ---------- 运维管理人员的权限边界 ----------
            // 知识的审核 / 重建向量 / 删除：仅管理员
            boolean adminOnly = (uri.contains("/kb/document/")
                    && (uri.endsWith("/audit") || uri.endsWith("/reindex")))
                    || ("DELETE".equalsIgnoreCase(method) && uri.startsWith("/api/kb"));
            if (adminOnly && !"admin".equals(role)) {
                write(response, 403, "该操作需要管理员权限");
                return false;
            }

            request.setAttribute("username", session.username());
            request.setAttribute("role", role);
            return true;
        }

        private void write(HttpServletResponse response, int code, String message) throws Exception {
            response.setStatus(code);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\"}");
        }
    }
}
