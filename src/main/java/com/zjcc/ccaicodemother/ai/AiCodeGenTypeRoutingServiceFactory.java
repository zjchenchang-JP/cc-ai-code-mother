package com.zjcc.ccaicodemother.ai;

import com.zjcc.ccaicodemother.utils.SpringContextUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI代码生成类型路由服务工厂
 *
 * @author yupi
 */
@Slf4j
@Configuration
public class AiCodeGenTypeRoutingServiceFactory {

    /**
     * 创建AI代码生成类型路由服务实例
     * 注意：不能加 @Bean！@Configuration 中 @Bean 方法会被 CGLIB 拦截，
     * 任何调用都返回容器里的同一个单例（方法体不重复执行），"每次新实例"就被架空了。
     * 普通方法直接调用才会真正执行方法体，每次 build 出独立实例（独享 guardrail 缓存 + prototype ChatModel）
     */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
        // 动态获取多例的路由 ChatModel，支持并发
        ChatModel chatModel = SpringContextUtil.getBean("routingChatModelPrototype", ChatModel.class);
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 默认提供一个 Bean
     * 兼容旧有逻辑（按类型注入 AiCodeGenTypeRoutingService 的地方）
     */
    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
        return createAiCodeGenTypeRoutingService();
    }
}
