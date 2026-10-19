package com.opsai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 桌面版专用：让 Spring Boot 直接托管前端页面。
 *
 * <p>桌面版 exe 里没有 nginx，前端打包产物被塞进 {@code classpath:/static/}，
 * 由这里负责两件事：
 * <ol>
 *   <li>能找到的静态文件（js/css/图片）照常返回；</li>
 *   <li>找不到的路径回退到 {@code index.html}——这是单页应用（SPA）的必要处理，
 *       否则用户刷新 {@code /selfheal} 这类前端路由会 404。</li>
 * </ol>
 * {@code /api/**} 明确不参与回退：接口不存在时应该返回 404，而不是返回一个 HTML 页面。
 *
 * <p>如果构建时没有把前端产物放进来（比如只跑 Docker 部署），
 * 这里会因为找不到 index.html 而直接返回 null，行为和原来一致，不影响 Docker 方式。
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 接口路径不参与 SPA 回退
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 前端路由：回退到 index.html
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
