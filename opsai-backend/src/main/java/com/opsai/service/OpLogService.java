package com.opsai.service;

import com.opsai.entity.SysLog;
import com.opsai.repo.SysLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作日志：把「谁、什么时候、做了什么、花了多久」记到 sys_log 表。
 * <p>
 * 运维管理端靠它追溯「这个答案是怎么来的」——
 * 比如某天某个用户问了什么、命中了哪几篇资料、用了多少毫秒，都能查回去。
 * <p>
 * 记日志本身失败不能影响主流程，所以这里全部 catch 掉只打 warn。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpLogService {

    private final SysLogRepository repo;

    public void log(String username, String action, String detail, String ip, Integer costMs) {
        try {
            SysLog s = new SysLog();
            s.setUsername(username == null ? "unknown" : username);
            s.setAction(action);
            s.setDetail(truncate(detail, 500));
            s.setIp(ip);
            s.setCostMs(costMs);
            s.setCreateTime(LocalDateTime.now());
            repo.save(s);
        } catch (Exception e) {
            log.warn("写操作日志失败（不影响主流程）: {}", e.getMessage());
        }
    }

    public void log(String username, String action, String detail) {
        log(username, action, detail, null, null);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
