package com.zjcc.ccaicodemother.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zjcc.ccaicodemother.ai.tools.*;
import com.zjcc.ccaicodemother.exception.BusinessException;
import com.zjcc.ccaicodemother.exception.ErrorCode;
import com.zjcc.ccaicodemother.model.enums.CodeGenTypeEnum;
import com.zjcc.ccaicodemother.service.ChatHistoryService;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 服务创建工厂
 */
@Configuration
@Slf4j
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    // 流式模型（starter 自动配置的）
    @Resource(name = "openAiStreamingChatModel")
    private StreamingChatModel streamingChatModel;

    @Resource // 会话记忆
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamingChatModel reasoningStreamingChatModel;

    /**
     * AI 服务实例缓存 性能优化
     * 缓存策略：
     * - 最大缓存 1000 个实例
     * - 写入后 30 分钟过期
     * - 访问后 10 分钟过期
     * 把每轮对话的“查库+灌 Redis”变成每 app 每10分钟聊天周期一次，数据库和 Redis 的负载显著下降；而用户侧的等待时间依旧由 AI 推理独占。缓存最常见的真实收益形态：救基础设施，不救体感
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，appId: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取服务（带缓存）兼容历史逻辑
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        // 命中缓存则返回，否则调用createAiCodeGeneratorService 创建新实例
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId 和代码生成类型获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
    }

    /**
     * 创建新的 AI 服务实例
     * 根据 appId 获取服务
     * 之前所有应用共用同一个 AI Service 实例
     * 如果想隔离会话记忆，可以给每个应用分配一个专属的 AI Service
     * 每个 AI Service 通过appId绑定独立的对话记忆
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(Long appId, CodeGenTypeEnum codeGenType) {
        // 根据 appId 构建独立的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(20)
                .build();
        // 初始化AI Service 对话记忆 从数据库加载历史对话到记忆中
        // MessageWindowChatMemory.add() 每加一条消息都会“读出整窗→追加→整窗写回”，20 条历史就是几十次 Redis 往返
        // 每次调用loadChatHistoryToMemory：MySQL 查询 + clear + 20×Redis 读 + 20×Redis 写
        chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);
        // 根据代码生成类型选择不同的模型配置
        return switch (codeGenType) {
            // Vue 项目生成使用推理模型
            case VUE_PROJECT -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(reasoningStreamingChatModel)
                    // 必须指定 chatMemoryProvider 配置，为每个 memoryId 绑定会话记忆
                    .chatMemoryProvider(memoryId -> chatMemory)
                    .tools(
                            new FileWriteTool(),
                            new FileReadTool(),
                            new FileModifyTool(),
                            new FileDirReadTool(),
                            new FileDeleteTool()
                    )
                    // 幻觉工具名称策略 配置了找不到工具时的处理策略
                    // 让框架帮我们处理 AI 出现幻觉的情况 比如告诉 AI “找不到工具”
                    // TODO 优化幻觉处理策略
                    // 调大对话记忆的容量，否则 AI 会中途断片儿，忘记已经生成了哪些文件
                    // 尝试换其他的 AI 大模型
                    // 优化提示词
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                            toolExecutionRequest, "Error: there is no tool called " + toolExecutionRequest.name()
                    ))
                    .build();
            // HTML 和多文件生成使用默认模型
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(streamingChatModel)
                    .chatMemory(chatMemory)
                    .build();
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
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

    /**
     * 构建缓存键
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return appId + "_" + codeGenType.getValue();
    }
}
