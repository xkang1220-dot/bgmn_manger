package com.kk.system.support;

import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskQueryRateLimiter {

    private static final String FAIL_PREFIX = "kk:task-query:fail:";
    private static final String OK_PREFIX = "kk:task-query:ok:";
    private static final int FAIL_MAX = 8;
    private static final int OK_MAX = 30;
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(15);
    private static final Duration OK_WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;

    public void assertAllowed(String clientIp) {
        String ip = sanitize(clientIp);
        try {
            if (current(FAIL_PREFIX + ip) >= FAIL_MAX || current(OK_PREFIX + ip) >= OK_MAX) {
                throw tooFrequent();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("task query rate limiter unavailable: {}", e.getMessage());
            throw new BusinessException("查询服务暂不可用，请稍后再试");
        }
    }

    public void recordFail(String clientIp) {
        bump(FAIL_PREFIX + sanitize(clientIp), FAIL_WINDOW);
    }

    public void recordSuccess(String clientIp) {
        bump(OK_PREFIX + sanitize(clientIp), OK_WINDOW);
    }

    private long current(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return FAIL_MAX;
        }
    }

    private void bump(String key, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, window);
                return;
            }
            Long ttl = redis.getExpire(key);
            if (ttl != null && ttl < 0) {
                redis.expire(key, window);
            }
        } catch (Exception e) {
            log.warn("task query rate limiter increment failed: {}", e.getMessage());
        }
    }

    private static String sanitize(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(clientIp.length());
        for (int i = 0; i < clientIp.length(); i++) {
            char c = clientIp.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == ':' || c == '-') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "unknown" : sb.toString();
    }

    private static BusinessException tooFrequent() {
        return new BusinessException(429, "尝试过于频繁，请稍后再试");
    }
}
