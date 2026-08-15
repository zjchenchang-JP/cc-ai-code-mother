package com.zjcc.ccaicodemother;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true) // 可以通过 AopContext.currentProxy() 获取当前的代理对象
public class CcAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcAiCodeMotherApplication.class, args);
    }

}
