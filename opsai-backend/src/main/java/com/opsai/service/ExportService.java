package com.opsai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsai.entity.FaultTicket;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 处理单导出：把一次诊断的全过程整理成正式的 Word 处理单。
 * 这是最能体现「有据可查」的产物。
 */
@Service
public class ExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> DEVICE_CN = Map.of(
            "server", "服务器", "db", "数据库", "network", "网络",
            "middleware", "中间件", "app", "应用");

    private static final Map<String, String> STATUS_CN = Map.of(
            "0", "待诊断", "1", "已诊断", "2", "已处理", "3", "已关闭");

    @SuppressWarnings("unchecked")
    public byte[] buildTicketDocx(Map<String, Object> detail) throws Exception {
        FaultTicket ticket = (FaultTicket) detail.get("ticket");
        List<Map<String, Object>> records = (List<Map<String, Object>>) detail.get("records");

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 标题
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = title.createRun();
            tr.setText("IT 运维故障处理单");
            tr.setBold(true);
            tr.setFontSize(20);
            tr.setFontFamily("微软雅黑");

            // 基本信息表
            XWPFTable info = doc.createTable(4, 4);
            info.setWidth("100%");
            setCell(info, 0, 0, "故障单号");
            setCell(info, 0, 1, ticket.getTicketNo());
            setCell(info, 0, 2, "提交时间");
            setCell(info, 0, 3, ticket.getCreateTime() == null ? "" : ticket.getCreateTime().format(FMT));

            setCell(info, 1, 0, "故障标题");
            setCell(info, 1, 1, ticket.getTitle());
            setCell(info, 1, 2, "设备类型");
            setCell(info, 1, 3, cn(DEVICE_CN, ticket.getDeviceType()));

            setCell(info, 2, 0, "故障类别");
            setCell(info, 2, 1, ticket.getFaultCategory());
            setCell(info, 2, 2, "处理状态");
            setCell(info, 2, 3, cn(STATUS_CN, String.valueOf(ticket.getStatus())));

            setCell(info, 3, 0, "导出时间");
            setCell(info, 3, 1, LocalDateTime.now().format(FMT));
            setCell(info, 3, 2, "诊断模型");
            setCell(info, 3, 3, records != null && !records.isEmpty()
                    ? String.valueOf(records.get(0).get("modelName")) : "-");

            heading(doc, "一、故障现象");
            body(doc, nvl(ticket.getPhenomenon()));

            heading(doc, "二、日志原文");
            String log = nvl(ticket.getLogText());
            if (log.length() > 3000) log = log.substring(0, 3000) + "\n…（日志过长，此处截断，完整内容见系统）";
            body(doc, log.isBlank() ? "（未提供日志）" : log);

            heading(doc, "三、诊断结论与排查建议");

            if (records == null || records.isEmpty()) {
                body(doc, "（该故障单还没有诊断记录）");
            } else {
                int round = 1;
                for (Map<String, Object> rec : records) {
                    JsonNode answer = (JsonNode) rec.get("answer");
                    boolean isFollowUp = answer != null && answer.has("followUp");

                    XWPFParagraph rp = doc.createParagraph();
                    XWPFRun rr = rp.createRun();
                    rr.setBold(true);
                    rr.setFontSize(12);
                    rr.setFontFamily("微软雅黑");

                    if (isFollowUp) {
                        rr.setText("追问 " + (round - 1) + "：" + nvl(rec.get("keywords")));
                        body(doc, answer.path("followUp").asText(""));
                    } else {
                        rr.setText("诊断轮次 " + round);
                        writeDiagnosis(doc, answer);
                    }

                    XWPFParagraph meta = doc.createParagraph();
                    XWPFRun mr = meta.createRun();
                    mr.setFontSize(9);
                    mr.setColor("808080");
                    mr.setText("耗时：首字 " + nvl(rec.get("firstTokenMs")) + " ms / 总计 "
                            + nvl(rec.get("costMs")) + " ms　｜　"
                            + (Integer.valueOf(1).equals(rec.get("adopted")) ? "建议已采纳" : "建议未标记采纳"));

                    writeHits(doc, (JsonNode) rec.get("hits"));
                    round++;
                }
            }

            heading(doc, "四、处理结果确认");
            XWPFTable sign = doc.createTable(2, 2);
            setCell(sign, 0, 0, "处理人签字");
            setCell(sign, 0, 1, "");
            setCell(sign, 1, 0, "确认日期");
            setCell(sign, 1, 1, "");

            XWPFParagraph foot = doc.createParagraph();
            XWPFRun fr = foot.createRun();
            fr.setFontSize(9);
            fr.setColor("808080");
            fr.setText("本处理单由「本地运维故障智能辅助排查工具」自动生成，诊断结论基于本地大模型与本地知识库，全程未调用外部服务。");

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void writeDiagnosis(XWPFDocument doc, JsonNode answer) {
        if (answer == null) {
            body(doc, "（无诊断内容）");
            return;
        }
        body(doc, "结论：" + answer.path("summary").asText(""));

        JsonNode causes = answer.path("causes");
        int i = 1;
        for (JsonNode c : causes) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r = p.createRun();
            r.setBold(true);
            r.setFontFamily("微软雅黑");
            r.setText(i + ". 可能原因：" + c.path("cause").asText("")
                    + "（可信度 " + c.path("confidence").asText("") + "）");

            body(doc, "依据：" + c.path("evidence").asText(""));

            XWPFParagraph sp = doc.createParagraph();
            XWPFRun sr = sp.createRun();
            sr.setFontFamily("微软雅黑");
            sr.setText("排查步骤：");
            int j = 1;
            for (JsonNode s : c.path("checkSteps")) {
                bullet(doc, j++ + ") " + s.asText(""));
            }

            body(doc, "处置建议：" + c.path("fix").asText(""));
            i++;
        }

        JsonNode more = answer.path("needMoreInfo");
        if (more.isArray() && more.size() > 0) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r = p.createRun();
            r.setBold(true);
            r.setFontFamily("微软雅黑");
            r.setText("还需要补充的信息：");
            for (JsonNode m : more) {
                bullet(doc, m.asText(""));
            }
        }
    }

    private void writeHits(XWPFDocument doc, JsonNode hits) {
        if (hits == null || !hits.isArray() || hits.isEmpty()) return;

        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontFamily("微软雅黑");
        r.setText("引用依据（知识库检索命中）：");

        int i = 1;
        for (JsonNode h : hits) {
            XWPFParagraph hp = doc.createParagraph();
            XWPFRun hr = hp.createRun();
            hr.setFontSize(10);
            hr.setFontFamily("微软雅黑");
            String content = h.path("content").asText("");
            if (content.length() > 150) content = content.substring(0, 150) + "…";
            hr.setText("[资料" + i + "] " + h.path("title").asText("")
                    + "（相似度 " + String.format("%.2f", h.path("score").asDouble()) + "）：" + content);
            i++;
        }
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(200);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(13);
        r.setFontFamily("微软雅黑");
    }

    /**
     * 写一段正文。
     * 注意：POI 的 setText 不会把 \n 当成换行，日志原文是一行一条的，
     * 必须自己按行拆开并用 addBreak 插入换行，否则整段日志会挤成一坨。
     */
    private void body(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        writeLines(p, text, 11);
    }

    private void writeLines(XWPFParagraph p, String text, int fontSize) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            XWPFRun r = p.createRun();
            r.setFontSize(fontSize);
            r.setFontFamily("微软雅黑");
            if (i > 0) r.addBreak();
            r.setText(lines[i]);
        }
    }

    private void bullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(400);
        writeLines(p, text, 11);
    }

    private void setCell(XWPFTable table, int row, int col, String text) {
        XWPFTableCell cell = table.getRow(row).getCell(col);
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun r = p.createRun();
        r.setText(text == null ? "" : text);
        r.setFontSize(10);
        r.setFontFamily("微软雅黑");
        if (col % 2 == 0) r.setBold(true);
    }

    private String cn(Map<String, String> map, String key) {
        if (key == null) return "";
        return map.getOrDefault(key, key);
    }

    private String nvl(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
