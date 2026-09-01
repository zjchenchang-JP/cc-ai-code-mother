package com.zjcc.ccaicodemother.ai;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务创建工厂
 */
@Configuration
@Slf4j
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    // 流式模型
    @Resource
    private StreamingChatModel streamingChatModel;

    @Resource // 会话记忆
    private RedisChatMemoryStore redisChatMemoryStore;

    /**
     * 根据 appId 获取服务
     * 之前所有应用共用同一个 AI Service 实例
     * 如果想隔离会话记忆，可以给每个应用分配一个专属的 AI Service
     * 每个 AI Service 通过appId绑定独立的对话记忆
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        // 根据 appId 构建独立的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(20)
                .build();
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemory(chatMemory)
                .build();
    }

    /**
     * 代码优化 开闭原则
     * 为了保证跟之前的代码兼容，仍然默认提供一个appId = 0的 AI Service 的 Bean
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        // 简写：只适合"单模型、无附加能力"
        // return AiServices.create(AiCodeGeneratorService.class, chatModel);

        return getAiCodeGeneratorService(0L);
    }
}
