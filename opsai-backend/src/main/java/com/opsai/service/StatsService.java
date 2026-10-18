package com.opsai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsai.entity.DiagnoseRecord;
import com.opsai.entity.FaultTicket;
import com.opsai.repo.DiagnoseRecordRepository;
import com.opsai.repo.FaultTicketRepository;
import com.opsai.repo.KbChunkRepository;
import com.opsai.repo.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计服务：给前端统计页提供数据，也为性能分析提供数据。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final FaultTicketRepository ticketRepo;
    private final DiagnoseRecordRepository recordRepo;
    private final KbDocumentRepository docRepo;
    private final KbChunkRepository chunkRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> overview() {
        List<FaultTicket> tickets = ticketRepo.findAll();
        List<DiagnoseRecord> records = recordRepo.findAll();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketCount", tickets.size());
        out.put("recordCount", records.size());
        out.put("documentCount", docRepo.count());
        out.put("publishedCount", docRepo.countByStatus(1));
        out.put("chunkCount", chunkRepo.count());

        // 故障类别分布（饼图）
        out.put("categoryDist", countBy(tickets, t ->
                t.getFaultCategory() == null || t.getFaultCategory().isBlank() ? "未分类" : t.getFaultCategory()));

        // 设备类型分布
        out.put("deviceDist", countBy(tickets, t ->
                t.getDeviceType() == null || t.getDeviceType().isBlank() ? "未分类" : t.getDeviceType()));

        // 首字耗时 / 总耗时（性能指标，项目需求要求首字 5 秒内）
        List<Integer> firstTokens = records.stream()
                .map(DiagnoseRecord::getFirstTokenMs).filter(Objects::nonNull).toList();
        List<Integer> costs = records.stream()
                .map(DiagnoseRecord::getCostMs).filter(Objects::nonNull).toList();

        out.put("avgFirstTokenMs", avg(firstTokens));
        out.put("maxFirstTokenMs", firstTokens.stream().mapToInt(Integer::intValue).max().orElse(0));
        out.put("avgCostMs", avg(costs));
        out.put("firstTokenUnder5sRate", rate(
                firstTokens.stream().filter(v -> v <= 5000).count(), firstTokens.size()));

        // 每小时的调用量（柱图，看使用高峰）
        out.put("hourDist", countBy(records, r ->
                r.getCreateTime() == null ? "未知" : String.format("%02d:00", r.getCreateTime().getHour())));

        // 采纳率
        long adopted = records.stream().filter(r -> r.getAdopted() != null && r.getAdopted() == 1).count();
        out.put("adoptedCount", adopted);
        out.put("adoptRate", rate(adopted, records.size()));

        // 每天诊断量（趋势）
        Map<String, Long> daily = records.stream()
                .filter(r -> r.getCreateTime() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getCreateTime().toLocalDate().toString(),
                        TreeMap::new, Collectors.counting()));
        out.put("dailyTrend", daily);

        // 最近 10 条诊断的耗时，用于折线图
        List<Map<String, Object>> latency = records.stream()
                .filter(r -> r.getCostMs() != null)
                .sorted(Comparator.comparing(DiagnoseRecord::getCreateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(10)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ticketId", r.getTicketId());
                    m.put("firstTokenMs", r.getFirstTokenMs());
                    m.put("costMs", r.getCostMs());
                    m.put("time", r.getCreateTime() == null ? "" : r.getCreateTime().toString());
                    return m;
                })
                .toList();
        out.put("latency", latency);

        return out;
    }

    /** 案例详情：故障单 + 它的所有诊断记录（含命中的资料） */
    public Map<String, Object> ticketDetail(Long ticketId) {
        FaultTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("故障单不存在：" + ticketId));
        List<DiagnoseRecord> records = recordRepo.findByTicketIdOrderByCreateTimeDesc(ticketId);

        List<Map<String, Object>> recViews = new ArrayList<>();
        for (DiagnoseRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("keywords", r.getKeywords());
            m.put("firstTokenMs", r.getFirstTokenMs());
            m.put("costMs", r.getCostMs());
            m.put("adopted", r.getAdopted());
            m.put("modelName", r.getModelName());
            m.put("createTime", r.getCreateTime() == null ? null : r.getCreateTime().toString());
            m.put("answer", parse(r.getAnswerJson()));
            m.put("hits", parse(r.getHitsJson()));
            m.put("templates", parse(r.getTemplatesJson()));
            recViews.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", ticket);
        out.put("records", recViews);
        return out;
    }

    /** 案例列表（带最近一次诊断的摘要） */
    public List<Map<String, Object>> ticketList() {
        List<FaultTicket> tickets = ticketRepo.findAllByOrderByCreateTimeDesc();
        List<Map<String, Object>> list = new ArrayList<>();
        for (FaultTicket t : tickets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("ticketNo", t.getTicketNo());
            m.put("title", t.getTitle());
            m.put("deviceType", t.getDeviceType());
            m.put("faultCategory", t.getFaultCategory());
            m.put("status", t.getStatus());
            m.put("createTime", t.getCreateTime() == null ? null : t.getCreateTime().toString());

            recordRepo.findFirstByTicketIdOrderByCreateTimeDesc(t.getId()).ifPresent(r -> {
                m.put("firstTokenMs", r.getFirstTokenMs());
                m.put("costMs", r.getCostMs());
                m.put("adopted", r.getAdopted());
                JsonNode node = parse(r.getAnswerJson());
                m.put("summary", node == null ? null
                        : node.path("summary").asText(node.has("followUp") ? node.path("followUp").asText("") : ""));
            });
            list.add(m);
        }
        return list;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode().put("raw", json);
        }
    }

    private <T> Map<String, Long> countBy(List<T> list, java.util.function.Function<T, String> key) {
        Map<String, Long> map = list.stream()
                .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
        return new LinkedHashMap<>(map);
    }

    private int avg(List<Integer> values) {
        return values.isEmpty() ? 0
                : (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private double rate(long part, long total) {
        return total == 0 ? 0d : Math.round(part * 10000.0 / total) / 100.0;
    }
}
