package com.opsai.controller;

import com.opsai.entity.SelfHealEvent;
import com.opsai.selfheal.SelfHealAction;
import com.opsai.selfheal.SelfHealActionRegistry;
import com.opsai.service.SelfHealService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自愈接口：巡检、事件查询、诊断、执行。
 *
 * <p>权限：
 * <ul>
 *   <li>查看指标 / 事件 / 触发巡检：工程师及以上</li>
 *   <li><b>执行处置动作：仅管理员</b>（在 WebConfig 里按 URI 拦截）</li>
 *   <li>只读用户完全看不到这一块</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/selfheal")
@RequiredArgsConstructor
public class SelfHealController {

    private final SelfHealService service;
    private final SelfHealActionRegistry registry;

    /** 当前健康指标 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return service.metrics();
    }

    /** 事件列表 */
    @GetMapping("/events")
    public List<SelfHealEvent> events() {
        return service.list();
    }

    @GetMapping("/events/{id}")
    public SelfHealEvent detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 手动触发一次巡检（不执行任何动作） */
    @PostMapping("/scan")
    public Map<String, Object> scan() {
        List<SelfHealEvent> created = service.scan();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created.size());
        out.put("events", created);
        return out;
    }

    /** 对事件跑一次 AI 诊断（耗时较长，约 20~40 秒） */
    @PostMapping("/events/{id}/diagnose")
    public SelfHealEvent diagnose(@PathVariable Long id) throws Exception {
        return service.diagnose(id);
    }

    /** 重新生成 dry-run 预览 */
    @PostMapping("/events/{id}/propose")
    public SelfHealEvent propose(@PathVariable Long id) {
        return service.propose(id);
    }

    /** 执行处置动作 —— 仅管理员，且必须由人明确点击 */
    @PostMapping("/events/{id}/execute")
    public SelfHealEvent execute(@PathVariable Long id, HttpServletRequest http) {
        String who = http.getAttribute("username") == null ? "unknown" : String.valueOf(http.getAttribute("username"));
        return service.execute(id, who);
    }

    /** 忽略事件 */
    @PostMapping("/events/{id}/ignore")
    public SelfHealEvent ignore(@PathVariable Long id, HttpServletRequest http) {
        String who = http.getAttribute("username") == null ? "unknown" : String.valueOf(http.getAttribute("username"));
        return service.ignore(id, who);
    }

    /** 白名单动作清单（前端展示"系统具备哪些自愈能力"） */
    @GetMapping("/actions")
    public List<Map<String, Object>> actions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SelfHealAction a : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a.key());
            m.put("title", a.title());
            m.put("risk", a.risk());
            out.add(m);
        }
        return out;
    }
}
