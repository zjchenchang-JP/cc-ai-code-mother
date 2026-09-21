package com.zjcc.ccaicodemother.ratelimiter.aspect;

import com.zjcc.ccaicodemother.exception.BusinessException;
import com.zjcc.ccaicodemother.exception.ErrorCode;
import com.zjcc.ccaicodemother.model.entity.User;
import com.zjcc.ccaicodemother.ratelimiter.annotation.RateLimit;
import com.zjcc.ccaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 限流切面核心逻辑
 *
 * @author zjchenchang
 * @createDate 2026/9/21 13:30
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    // @Lazy 注入代理：真正的 RedissonClient 在第一次调用方法时才创建（配合 RedissonConfig 的 @Lazy @Bean）
    @Lazy
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private UserService userService;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        String key = generateRateLimitKey(point, rateLimit);
        // 使用Redisson的分布式限流器
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.expire(Duration.ofHours(1)); // 1 小时后过期
        // 设置限流器参数：每个时间窗口允许的请求数 和 间隔时间窗口
        rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(), RateIntervalUnit.SECONDS);
        // 尝试获取令牌，如果获取失败则限流
        if (!rateLimiter.tryAcquire(1)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
        }
    }

    /**
     * 生成限流key
     * 根据不同的限流策略生成唯一的 Redis key
     * API 级别按方法名、用户级别按用户 ID、IP 级别按客户端 IP，从而支持三种限流维度
     *
     * @param point
     * @param rateLimit
     * @return key
     */
    private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append("rate_limit:");
        // 添加注解声明的 自定义前缀
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(":");
        }
        // 根据限流类型生成不同的key
        switch (rateLimit.limitType()) {
            case API : {
                // 接口级别：方法名
                MethodSignature signature = (MethodSignature) point.getSignature();
                Method method = signature.getMethod();
                keyBuilder.append("api:").append(method.getDeclaringClass().getSimpleName())
                        .append(".").append(method.getName());
                break;
            }
            case USER : {
                // 用户级别：用户ID
                try {
                    ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder
                            .getRequestAttributes();
                    if (requestAttributes != null) {
                        HttpServletRequest request = requestAttributes.getRequest();
                        User loginUser = userService.getLoginUser(request);
                        keyBuilder.append("user:").append(loginUser.getId());
                    } else {
                        // 降级处理 用户级别限流获取用户信息失败时自动降级为 IP 限流
                        // 无法获取请求上下文，使用IP限流
                        keyBuilder.append("ip:").append(getClientIP());
                    }
                } catch (BusinessException e) {
                    // 未登录用户使用IP限流 降级处理
                    keyBuilder.append("ip:").append(getClientIP());
                }
                break;
            }
            case IP : {
                // IP级别：客户端IP
                keyBuilder.append("ip:").append(getClientIP());
                break;
            }
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
        }
        return keyBuilder.toString();
    }

    private String getClientIP() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 处理多级代理的情况
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }

}
