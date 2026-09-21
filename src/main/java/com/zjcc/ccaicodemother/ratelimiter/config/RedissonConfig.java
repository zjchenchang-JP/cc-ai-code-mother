package com.zjcc.ccaicodemother.ratelimiter.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private Integer redisPort;

    // 冒号语法：占位符默认值，yml 没配 password 时取空串（本地免密 Redis），避免 Could not resolve placeholder
    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database}")
    private Integer redisDatabase;

    /**
     * @Lazy 懒初始化：启动时不创建、不连 Redis，第一次被真正使用时才创建
     * 注意：配合注入方（RateLimitAspect）的 @Resource @Lazy 一起生效，
     * 只在这里加 @Lazy 的话，切面启动期一注入就还是会被提前创建
     */
    @Lazy
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10)
                .setIdleConnectionTimeout(30000)
                .setConnectTimeout(5000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        // 如果有密码则设置密码
        if (redisPassword != null && !redisPassword.isEmpty()) {
            singleServerConfig.setPassword(redisPassword);
        }
        // 急切连接——Redis 没启动时应用会在这一步直接启动失败
        // 生产上如果希望“Redis 短暂不可用不拖死应用启动”，加 lazy 初始化
        return Redisson.create(config);
    }
}
