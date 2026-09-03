package com.kk.biz.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生成不重复业务编号。前缀 + 时间 + 序号 + 短随机，降低重启/并发碰撞。
 */
@Component
public class BizNoGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final AtomicInteger seq = new AtomicInteger(0);

    public synchronized String next(String prefix) {
        int n = seq.updateAndGet(v -> v >= 9999 ? 1 : v + 1);
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return prefix + LocalDateTime.now().format(FMT) + String.format("%04d", n) + rand;
    }

    public String ledger() {
        return next("LG");
    }

    public String approval() {
        return next("AP");
    }

    public String rollback() {
        return next("RB");
    }
}
