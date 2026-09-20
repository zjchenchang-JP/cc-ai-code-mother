package com.zjcc.ccaicodemother.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Resource;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 缓存管理器配置
 * 序列化器选择：StringRedisSerializer 用于序列化 key，确保 Redis 中的 key 是可读的字符串；GenericJackson2JsonRedisSerializer 用于序列化 value，支持复杂对象的序列化和反序列化。
 * 时间类型支持：注册 JavaTimeModule 来支持 Java 8 时间类型 LocalDateTime
 * 差异化配置：既提供了默认配置，又为特定的缓存区域设置不同的过期时间
 */
@Configuration
public class RedisCacheManagerConfig {

    @Resource
    private RedisConnectionFactory redisConnectionFactory;

    @Bean
    public CacheManager cacheManager() {
        // 配置 ObjectMapper 支持 Java8 时间类型
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // 默认配置
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30)) // 默认 30 分钟过期
                .disableCachingNullValues() // 禁用 null 值缓存
                // key 使用 String 序列化器
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()));
                // // value 使用 JSON 序列化器（支持复杂对象）
                // //注意开启后需要给序列化增加默认类型配置，否则无法反序列化: 因为 Redis 中并没有存储 Java 类的信息，不知道要反序列化成哪个类，就会报错
                //.serializeValuesWith(RedisSerializationContext.SerializationPair
                //        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));
        
        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                // 针对 good_app_page 配置5分钟过期
                .withCacheConfiguration("good_app_page",
                        defaultConfig.entryTtl(Duration.ofMinutes(5)))
                .build();
    }
}
