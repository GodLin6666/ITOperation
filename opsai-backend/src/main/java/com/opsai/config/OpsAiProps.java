package com.opsai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 读取 application.yml 中 opsai.* 的配置。
 * 有了这个类，代码里就不用到处写 @Value，改配置只改 yml。
 */
@Data
@Component
@ConfigurationProperties(prefix = "opsai")
public class OpsAiProps {

    private Ollama ollama = new Ollama();
    private Rag rag = new Rag();
    private Storage storage = new Storage();
    private Auth auth = new Auth();

    @Data
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "qwen2.5:7b-instruct-q4_K_M";
        private String embedModel = "bge-m3";
        private double temperature = 0.2;
        private int numCtx = 4096;
        private int timeoutSeconds = 300;
    }

    @Data
    public static class Rag {
        private int topK = 4;
        private double minScore = 0.35;
        private int chunkSize = 400;
        private int chunkOverlap = 80;

        /**
         * 是否把嵌入模型（bge-m3）放到 CPU 上跑。
         * <p>
         * 为什么默认开：本机是 6GB 显存的 4050，qwen2.5-7B 量化后已占约 4.7GB，
         * 只剩 1.2GB 左右。bge-m3 约 1.2GB，两个模型塞不进显存时 Ollama 会把对话模型
         * 换出，下一次对话就要重新从硬盘加载（实测每次多花约 5 秒，首字耗时直接超标）。
         * 把只有 0.5GB 级别计算量的嵌入模型放到 CPU（首次加载约 2 秒、之后每次约 90 毫秒），
         * 显存就完全留给对话模型，首字耗时立刻回到 1 秒级。
         * 如果以后换了大显存显卡，把这里改成 false 即可。
         */
        private boolean embedOnCpu = true;
    }

    @Data
    public static class Storage {
        private String uploadDir = "./data/logs";
    }

    @Data
    public static class Auth {
        private int tokenHours = 12;
    }
}
