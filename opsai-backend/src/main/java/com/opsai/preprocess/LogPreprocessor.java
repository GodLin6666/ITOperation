package com.opsai.preprocess;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第一段流程：日志预处理。
 * 把一大段乱七八糟的日志压成「日志模板 + 关键词 + 摘要」。
 * <p>
 * 例：
 * 2026-10-09 10:12:31 ERROR Connection refused, port=3306, attempts=3
 * 2026-10-09 10:12:33 ERROR Connection refused, port=3306, attempts=3
 * 会被压成：ERROR Connection refused, port=&lt;NUM&gt;, attempts=&lt;NUM&gt;（出现 2 次）
 */
@Component
public class LogPreprocessor {

    // 时间戳、IP、长十六进制串、纯数字，都是「会变的部分」，统一换成占位符
    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}([.,]\\d{1,6})?");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern HEX = Pattern.compile("\\b(0x)?[0-9a-fA-F]{8,}\\b");
    private static final Pattern NUM = Pattern.compile("\\d+");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]{2,}");

    /** 日志级别，用来给行排优先级（异常行优先送进模型） */
    private static final Pattern LEVEL = Pattern.compile("\\b(ERROR|FATAL|WARN|WARNING|Exception|SEVERE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "error", "info",
            "warn", "debug", "trace", "java", "lang", "org", "com", "http",
            "are", "was", "were", "not", "but", "you", "can", "has", "have", "been");

    /** 送进模型的日志行数上限，防止超过 num_ctx 被截断（避免超出模型上下文） */
    private static final int MAX_LINES = 80;

    public record Template(String pattern, int count, String sample) {
    }

    public record PreprocessResult(List<Template> templates, List<String> keywords, String summary,
                                   int rawLineCount, int usedLineCount) {
    }

    public PreprocessResult process(String phenomenon, String logText) {
        String raw = (phenomenon == null ? "" : phenomenon) + "\n" + (logText == null ? "" : logText);
        List<String> allLines = raw.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> lines = pickImportant(allLines);

        Map<String, int[]> counter = new LinkedHashMap<>();
        Map<String, String> samples = new LinkedHashMap<>();
        for (String line : lines) {
            String tpl = templatize(line);
            counter.computeIfAbsent(tpl, k -> new int[1])[0]++;
            samples.putIfAbsent(tpl, line);
        }

        List<Template> templates = counter.entrySet().stream()
                .map(e -> new Template(e.getKey(), e.getValue()[0], samples.get(e.getKey())))
                .sorted(Comparator.comparingInt(Template::count).reversed())
                .limit(20)
                .toList();

        Map<String, Integer> freq = new HashMap<>();
        Matcher m = WORD.matcher(raw.toLowerCase());
        while (m.find()) {
            String w = m.group();
            if (w.length() < 3 || STOP.contains(w)) continue;
            freq.merge(w, 1, Integer::sum);
        }
        List<String> keywords = freq.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(15)
                .map(Map.Entry::getKey)
                .toList();

        return new PreprocessResult(templates, keywords,
                buildSummary(phenomenon, templates, keywords),
                allLines.size(), lines.size());
    }

    /**
     * 日志太长时只保留最重要的行：异常/告警行优先，其余按原顺序补齐。
     * 这样既有足够的上下文，又不会超 num_ctx。
     */
    private List<String> pickImportant(List<String> lines) {
        if (lines.size() <= MAX_LINES) return lines;

        List<String> important = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String line : lines) {
            if (LEVEL.matcher(line).find()) {
                important.add(line);
            } else {
                others.add(line);
            }
        }
        List<String> result = new ArrayList<>(important.size() + others.size());
        result.addAll(important);
        int remain = MAX_LINES - result.size();
        if (remain > 0) {
            // 保留最后 remain 行普通日志（通常是最靠近故障时刻的上下文）
            result.addAll(others.subList(Math.max(0, others.size() - remain), others.size()));
        }
        return result.size() > MAX_LINES ? result.subList(0, MAX_LINES) : result;
    }

    private String templatize(String line) {
        String s = TS.matcher(line).replaceAll("<TS>");
        s = IP.matcher(s).replaceAll("<IP>");
        s = HEX.matcher(s).replaceAll("<HEX>");
        s = NUM.matcher(s).replaceAll("<NUM>");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private String buildSummary(String phenomenon, List<Template> templates, List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        if (phenomenon != null && !phenomenon.isBlank()) {
            sb.append("现象描述：").append(phenomenon.trim()).append("\n");
        }
        sb.append("关键词：").append(String.join("、", keywords)).append("\n");
        sb.append("日志模板（按出现次数排序）：\n");
        templates.stream().limit(8).forEach(t ->
                sb.append("- ").append(t.pattern()).append("（出现 ").append(t.count()).append(" 次）\n"));
        return sb.toString();
    }
}
