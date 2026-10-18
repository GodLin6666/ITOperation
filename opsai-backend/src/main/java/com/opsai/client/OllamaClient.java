package com.opsai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsai.config.OpsAiProps;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 和本地 Ollama 打交道的唯一入口。
 * 做三件事：非流式对话、流式对话（用来测首字耗时）、文本向量化。
 * 全部走 http://localhost:11434，不调用任何外部 API。
 */
@Component
public class OllamaClient {

    /**
     * 让模型常驻显存的时间。
     * <p>
     * Ollama 默认空闲 5 分钟就把模型从显存卸载，下次请求又要花好几秒重新从硬盘加载，
     * 首字耗时会直接从 1 秒级掉到 10 秒级（我实测到过 10285 ms）。
     * 这里在每个请求上显式带上 keep_alive，比让用户去 setx 设环境变量更可靠——
     * 换台机器、换个容器都不用重新配。
     */
    private static final String KEEP_ALIVE = "30m";

    private final OpsAiProps props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public OllamaClient(OpsAiProps props, RestClient.Builder builder) {
        this.props = props;
        this.rest = builder.baseUrl(props.getOllama().getBaseUrl()).build();
    }

    /** 非流式对话：一次返回全部结果 */
    public String chat(String system, String user) {
        JsonNode node = rest.post().uri("/api/chat")
                .body(Map.of(
                        "model", props.getOllama().getChatModel(),
                        "stream", false,
                        "keep_alive", KEEP_ALIVE,
                        "options", options(),
                        "messages", messages(system, user)))
                .retrieve().body(JsonNode.class);
        return node == null ? "" : node.path("message").path("content").asText();
    }

    /** 流式对话：边生成边回调，同时统计首字耗时（项目需求要求的性能指标） */
    public StreamResult chatStream(String system, String user, Consumer<String> onDelta) throws Exception {
        return chatStream(system, user, onDelta, props.getOllama().getTemperature(), props.getOllama().getNumCtx());
    }

    /** 可覆写采样参数的流式对话（追问场景会更啰嗦一点，用得上） */
    public StreamResult chatStream(String system, String user, Consumer<String> onDelta,
                                   double temperature, int numCtx) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("num_ctx", numCtx);

        String body = mapper.writeValueAsString(Map.of(
                "model", props.getOllama().getChatModel(),
                "stream", true,
                "keep_alive", KEEP_ALIVE,
                "options", options,
                "messages", messages(system, user)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getOllama().getBaseUrl() + "/api/chat"))
                .timeout(Duration.ofSeconds(props.getOllama().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.currentTimeMillis();
        long firstTokenMs = -1L;
        StringBuilder text = new StringBuilder();

        HttpResponse<java.util.stream.Stream<String>> resp =
                http.send(request, HttpResponse.BodyHandlers.ofLines());

        for (String line : (Iterable<String>) resp.body()::iterator) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            String delta = node.path("message").path("content").asText("");
            if (!delta.isEmpty()) {
                if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - start;
                text.append(delta);
                if (onDelta != null) onDelta.accept(delta);
            }
            if (node.path("done").asBoolean(false)) break;
        }
        if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - start;
        return new StreamResult(text.toString(), firstTokenMs, System.currentTimeMillis() - start);
    }

    /**
     * 文本向量化：返回 1024 维 double 数组。
     * <p>
     * 默认按配置把嵌入模型放到 CPU 上跑（num_gpu=0），原因是本机显存太紧：
     * 6GB 的 4050 装了 qwen2.5-7B 之后只剩约 1.2GB，再加载 bge-m3 会把对话模型
     * 挤出去，导致下次对话要重新加载模型，首字耗时从 1 秒涨到 6 秒。
     * 嵌入模型本身很小，放 CPU 上一次只要几十毫秒，这样显存全部留给对话模型。
     */
    public double[] embed(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getOllama().getEmbedModel());
        body.put("input", text == null ? "" : text);
        body.put("keep_alive", KEEP_ALIVE);
        if (props.getRag().isEmbedOnCpu()) {
            body.put("options", Map.of("num_gpu", 0));
        }

        JsonNode node = rest.post().uri("/api/embed")
                .body(body)
                .retrieve().body(JsonNode.class);
        JsonNode arr = node.path("embeddings").path(0);
        double[] vec = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            vec[i] = arr.get(i).asDouble();
        }
        return vec;
    }

    /** 探活：Ollama 是否在跑、两个模型是否都在 */
    public Map<String, Object> health() {
        JsonNode node = rest.get().uri("/api/tags").retrieve().body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (node != null) {
            for (JsonNode m : node.path("models")) {
                names.add(m.path("name").asText());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ollama", "up");
        out.put("models", names);
        out.put("chatModelReady", names.stream().anyMatch(n -> n.startsWith(props.getOllama().getChatModel())));
        out.put("embedModelReady", names.stream().anyMatch(n -> n.startsWith(props.getOllama().getEmbedModel())));
        return out;
    }

    private Map<String, Object> options() {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("temperature", props.getOllama().getTemperature());
        opt.put("num_ctx", props.getOllama().getNumCtx());
        return opt;
    }

    private List<Map<String, String>> messages(String system, String user) {
        List<Map<String, String>> list = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            list.add(Map.of("role", "system", "content", system));
        }
        list.add(Map.of("role", "user", "content", user));
        return list;
    }

    /** 一次流式对话的结果 */
    public record StreamResult(String text, long firstTokenMs, long costMs) {
    }
}
