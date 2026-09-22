package com.zjcc.ccaicodemother.config;

import com.zjcc.ccaicodemother.monitor.AiModelMonitorListener;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;

/**
 * 推理流式模型配置类
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.reasoning-streaming-chat-model")
@Data
public class ReasoningStreamingChatModelConfig {

    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    /**
     * 推理流式模型（用于 Vue 项目生成，带工具调用）
     */
    @Bean
    @Scope("prototype") //性能优化 多例模式
    public StreamingChatModel reasoningStreamingChatModelPrototype() {
        // 为了测试方便临时修改
        //final String modelName = "deepseek-chat";
        //final int maxTokens = 8192;
        // 生产环境使用：
        // final String modelName = "deepseek-reasoner";
        // final int maxTokens = 32768;
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener)) // 监听器注册到 AI 模型配置中
                // 显式指定 JDK HttpClient 传输层：classpath 同时存在 spring-restclient 与 jdk
                // 两个实现时 SPI 自动发现会报 Conflict；且 RestClient 读 chunked 响应会截断
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}
