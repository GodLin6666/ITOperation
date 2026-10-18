package com.opsai.controller;

import com.opsai.entity.SysLog;
import com.opsai.repo.SysLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 操作日志查询（运维管理端专用）。
 * <p>
 * 用途：用户反馈「助手给的答案不对」时，管理员可以按用户 / 动作 / 关键词翻日志，
 * 看到当时问了什么、命中了几篇资料、走了哪条处理路径，从而定位是检索没命中、
 * 还是知识库缺内容、还是模型答偏了。
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final SysLogRepository repo;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "200") int limit,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) String username,
                                    @RequestParam(required = false) String keyword) {

        List<SysLog> all = repo.findTop500ByOrderByCreateTimeDesc();

        List<SysLog> items = all.stream()
                .filter(x -> blank(action) || (x.getAction() != null && x.getAction().contains(action)))
                .filter(x -> blank(username) || (x.getUsername() != null && x.getUsername().contains(username)))
                .filter(x -> blank(keyword) ||
                        (x.getDetail() != null && x.getDetail().contains(keyword)) ||
                        (x.getAction() != null && x.getAction().contains(keyword)))
                .limit(Math.max(1, Math.min(limit, 500)))
                .collect(Collectors.toList());

        // 动作分布，给前端画个简单的图表
        Map<String, Long> byAction = new LinkedHashMap<>();
        all.stream()
                .collect(Collectors.groupingBy(
                        x -> x.getAction() == null ? "其它" : x.getAction(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> byAction.put(e.getKey(), e.getValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("returned", items.size());
        out.put("items", items);
        out.put("byAction", byAction);
        return out;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
