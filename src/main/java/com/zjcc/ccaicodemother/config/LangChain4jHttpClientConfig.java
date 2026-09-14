package com.zjcc.ccaicodemother.config;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j HTTP 传输层配置
 * 用同名 Bean 覆盖 starter 自动配置的 Spring RestClient 传输层（@ConditionalOnMissingBean 让位机制）
 *
 * 背景：Spring RestClient 处理 DeepSeek 的 chunked 响应（无 Content-Length）时
 * body 会被提前截断（只剩 "{"），导致 Jackson JsonEOFException。
 * JDK HttpClient（java.net.http）经最小复现验证可完整读取响应体，故切换。
 *
 * @author zjchenchang-JP
 */
@Configuration
public class LangChain4jHttpClientConfig {

    /**
     * 覆盖 openAiChatModel 的 HTTP 传输层
     */
    @Bean
    public HttpClientBuilder openAiChatModelHttpClientBuilder() {
        return new JdkHttpClientBuilder();
    }

    /**
     * 覆盖 openAiStreamingChatModel 的 HTTP 传输层
     */
    @Bean
    public HttpClientBuilder openAiStreamingChatModelHttpClientBuilder() {
        return new JdkHttpClientBuilder();
    }
}
