package com.opsai.selfheal;

import com.opsai.config.OpsAiProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 动作：清理过期的上传文件。
 * <p>
 * 用户上传的日志和截图会一直堆在 data/logs 下面，磁盘紧张时是第一批可以安全清掉的东西。
 * 只删「超过 7 天」的文件，正在用的、刚上传的都不动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanUploadCacheAction implements SelfHealAction {

    /** 保留天数：这个时间以内的文件一律不碰 */
    private static final int KEEP_DAYS = 7;

    private final OpsAiProps props;

    @Override
    public String key() {
        return "CLEAN_UPLOAD_CACHE";
    }

    @Override
    public String title() {
        return "清理过期的上传文件";
    }

    @Override
    public String risk() {
        return "LOW";
    }

    @Override
    public String plan() {
        List<Path> stale = scanStale();
        long bytes = stale.stream().mapToLong(this::sizeOf).sum();
        return String.format(
                "删除上传目录中超过 %d 天的文件。\n"
                        + "目录：%s\n"
                        + "将删除：%d 个文件，释放约 %s\n"
                        + "保留：最近 %d 天内的文件一律不动（包括正在排查中的日志和截图）。",
                KEEP_DAYS, uploadDir(), stale.size(), human(bytes), KEEP_DAYS);
    }

    @Override
    public String run() throws Exception {
        List<Path> stale = scanStale();
        long freed = 0;
        int deleted = 0;
        int failed = 0;
        for (Path p : stale) {
            long sz = sizeOf(p);
            try {
                Files.delete(p);
                freed += sz;
                deleted++;
            } catch (IOException e) {
                failed++;
                log.warn("[自愈] 删除失败 {}: {}", p, e.getMessage());
            }
        }
        String msg = String.format("删除 %d 个文件，释放 %s", deleted, human(freed))
                + (failed > 0 ? "，失败 " + failed + " 个" : "");
        log.info("[自愈] 清理上传目录: {}", msg);
        return msg;
    }

    @Override
    public Recheck recheck() {
        List<Path> left = scanStale();
        if (left.isEmpty()) {
            return new Recheck(true, "已无超过 " + KEEP_DAYS + " 天的过期文件，目录大小 " + human(dirSize()));
        }
        return new Recheck(false, "仍有 " + left.size() + " 个过期文件未能删除");
    }

    // ---------------- 内部工具 ----------------

    private Path uploadDir() {
        return Paths.get(props.getStorage().getUploadDir()).toAbsolutePath().normalize();
    }

    /** 扫描出所有超期的文件（不修改任何东西） */
    private List<Path> scanStale() {
        List<Path> out = new ArrayList<>();
        Path dir = uploadDir();
        if (!Files.isDirectory(dir)) return out;

        Instant cutoff = Instant.now().minus(Duration.ofDays(KEEP_DAYS));
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    FileTime t = Files.getLastModifiedTime(p);
                    if (t.toInstant().isBefore(cutoff)) out.add(p);
                } catch (IOException ignored) {
                    // 读不到时间就跳过，宁可不删
                }
            });
        } catch (IOException e) {
            log.warn("[自愈] 扫描上传目录失败: {}", e.getMessage());
        }
        return out;
    }

    private long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private long dirSize() {
        Path dir = uploadDir();
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(this::sizeOf).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
