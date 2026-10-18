package com.opsai.controller;

import com.opsai.config.OpsAiProps;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 日志文件上传：把上传的日志存到本地，并把文本内容回给前端填进日志框。
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long MAX_BYTES = 20 * 1024 * 1024;

    private final OpsAiProps props;

    @PostMapping("/log")
    public Map<String, Object> uploadLog(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("上传的文件是空的");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件超过 20MB，请先裁剪日志");

        Path dir = Paths.get(props.getStorage().getUploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String original = file.getOriginalFilename() == null ? "log.txt" : file.getOriginalFilename();
        String safe = original.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stored = LocalDateTime.now().format(STAMP) + "-" + safe;
        Path target = dir.resolve(stored);
        file.transferTo(target.toFile());

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        // 有些日志是 GBK 的，UTF-8 解出来会有大量替换字符，这里做一次兜底
        if (content.chars().filter(c -> c == 0xFFFD).count() > content.length() / 20) {
            content = new String(file.getBytes(), "GBK");
        }
        // 只回传前 200KB 给前端，避免把浏览器卡死
        if (content.length() > 200_000) {
            content = content.substring(0, 200_000) + "\n…（文件过长，仅载入前 200KB）";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", original);
        out.put("storedPath", target.toString());
        out.put("size", file.getSize());
        out.put("content", content);
        return out;
    }

    /** 允许的截图类型（只收图片，防止上传可执行文件） */
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");
    private static final long MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /**
     * 截图上传：普通用户贴的报错截图存到本地，返回访问路径。
     * 前端会用浏览器本地 OCR 把图中文字识别出来一起提问，原图则留档给运维人员复核。
     */
    @PostMapping("/image")
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("图片是空的");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片超过 8MB，请压缩后再试");

        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!IMAGE_TYPES.contains(type)) {
            throw new IllegalArgumentException("只支持 PNG / JPG / WEBP / GIF 格式的截图");
        }

        Path dir = Paths.get(props.getStorage().getUploadDir(), "images").toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String original = file.getOriginalFilename() == null ? "screenshot.png" : file.getOriginalFilename();
        String safe = original.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stored = LocalDateTime.now().format(STAMP) + "-" + safe;
        Path target = dir.resolve(stored);
        file.transferTo(target.toFile());

        String rel = props.getStorage().getUploadDir().replace("\\", "/") + "/images/" + stored;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", original);
        out.put("path", rel);
        out.put("size", file.getSize());
        return out;
    }
}
