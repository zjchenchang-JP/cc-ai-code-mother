package com.zjcc.ccaicodemother.config;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 通用流式对话模型配置
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
@Data
public class StreamingChatModelConfig {

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private boolean logRequests;

    private boolean logResponses;

    @Bean
    @Scope("prototype") //性能优化 多例模式 解决串行执行 并发阻塞问题 不然系统并发度只有1 同时只能1个客户使用
    public StreamingChatModel streamingChatModelPrototype() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                // 显式指定 JDK HttpClient 传输层：classpath 同时存在 spring-restclient 与 jdk
                // 两个实现时 SPI 自动发现会报 Conflict；且 RestClient 读 chunked 响应会截断
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}
