package com.visioncart.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class AiTraceService {
    private static final Logger log = LoggerFactory.getLogger(AiTraceService.class);

    public Instant start(String task, Map<String, ?> metadata) {
        log.info("ai_task_start task={} metadata={}", task, metadata);
        return Instant.now();
    }

    public void finish(String task, Instant started, String decision) {
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();
        log.info("ai_task_finish task={} decision={} elapsed_ms={}", task, decision, elapsedMs);
    }

    public void fail(String task, Instant started, Exception error, String recovery) {
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();
        log.warn("ai_task_fail task={} recovery={} elapsed_ms={} error={}", task, recovery, elapsedMs, error.toString());
    }
}
