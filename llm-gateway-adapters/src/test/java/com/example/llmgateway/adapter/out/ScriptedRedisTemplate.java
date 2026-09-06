package com.example.llmgateway.adapter.out;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

final class ScriptedRedisTemplate extends StringRedisTemplate {
    private final Queue<Long> results = new ArrayDeque<>();

    ScriptedRedisTemplate(Long... results) {
        for (Long result : results) {
            this.results.add(result);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        return (T) results.remove();
    }
}
