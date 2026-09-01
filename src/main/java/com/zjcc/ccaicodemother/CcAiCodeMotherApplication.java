package com.zjcc.ccaicodemother;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

//排除 embedding 的自动装配
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@EnableAspectJAutoProxy(exposeProxy = true) // 可以通过 AopContext.currentProxy() 获取当前的代理对象
@MapperScan("com.zjcc.ccaicodemother.mapper")
public class CcAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcAiCodeMotherApplication.class, args);
    }

}
