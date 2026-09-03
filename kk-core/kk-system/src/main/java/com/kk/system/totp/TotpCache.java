package com.kk.system.totp;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TotpCache {

    private static final String PENDING_PREFIX = "totp:pending:";
    private static final String REPLAY_PREFIX = "totp:replay:";

    private final Map<String, CacheEntry> store = new ConcurrentHashMap<>();

    public void set(String key, String value, long ttlSeconds) {
        store.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    public String get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt <= System.currentTimeMillis()) {
            store.remove(key);
            return null;
        }
        return entry.value;
    }

    public void delete(String key) {
        store.remove(key);
    }

    public boolean setIfAbsent(String key, String value, long ttlSeconds) {
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        CacheEntry created = store.compute(key, (k, existing) -> {
            if (existing != null && existing.expireAt > System.currentTimeMillis()) {
                return existing;
            }
            return new CacheEntry(value, expireAt);
        });
        return created.value.equals(value) && created.expireAt == expireAt;
    }

    public String pendingKey(long userId) {
        return PENDING_PREFIX + userId;
    }

    public String replayKey(long userId, long step) {
        return REPLAY_PREFIX + userId + ":" + step;
    }

    private record CacheEntry(String value, long expireAt) {
    }
}
