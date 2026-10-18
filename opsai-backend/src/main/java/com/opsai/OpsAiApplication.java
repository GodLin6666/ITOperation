package com.opsai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本地运维排障助手 - 启动类
 * <p>
 * 开启 {@code @EnableScheduling}：自愈功能需要定时巡检（见 SelfHealService#scheduledScan）。
 */
@SpringBootApplication
@EnableScheduling
public class OpsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsAiApplication.class, args);
    }
}
