package com.visioncart.service.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiTraceService {
    private static final Logger log = LoggerFactory.getLogger(AiTraceService.class);
    private static final int MAX_ACTIVE_TRACES = 10_000;
    private static final Duration TRACE_TTL = Duration.ofMinutes(15);

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, TraceEntry> activeTraces = new ConcurrentHashMap<>();

    public AiTraceService(MeterRegistry registry) {
        this.registry = registry;
    }

    public String start(String task, Map<String, ?> metadata) {
        log.info("ai_task_start task={} metadata={}", task, metadata);
        registry.counter("ai.task.calls", "task", task).increment();
        String traceId = UUID.randomUUID().toString();
        trimIfNeeded();
        activeTraces.put(traceId, new TraceEntry(Instant.now(), Timer.start(registry)));
        return traceId;
    }

    public void finish(String task, String traceId, String decision) {
        TraceEntry entry = activeTraces.remove(traceId);
        Instant started = entry != null ? entry.started() : Instant.now();
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();
        log.info("ai_task_finish task={} decision={} elapsed_ms={}", task, decision, elapsedMs);
        if (entry != null) {
            entry.sample().stop(registry.timer("ai.task.duration", "task", task));
        }
        registry.timer("ai.task.latency", "task", task, "decision", decision)
                .record(Duration.ofMillis(elapsedMs));
    }

    public void fail(String task, String traceId, Exception error, String recovery) {
        TraceEntry entry = activeTraces.remove(traceId);
        Instant started = entry != null ? entry.started() : Instant.now();
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();
        log.warn("ai_task_fail task={} recovery={} elapsed_ms={} error={}", task, recovery, elapsedMs, error.toString());
        if (entry != null) {
            entry.sample().stop(registry.timer("ai.task.duration", "task", task));
        }
        registry.counter("ai.task.failures", "task", task, "error", error.getClass().getSimpleName()).increment();
    }

    @Scheduled(fixedDelay = 300_000)
    void cleanupOrphanTraces() {
        Instant cutoff = Instant.now().minus(TRACE_TTL);
        activeTraces.entrySet().removeIf(entry -> entry.getValue().started().isBefore(cutoff));
    }

    private void trimIfNeeded() {
        if (activeTraces.size() <= MAX_ACTIVE_TRACES) {
            return;
        }
        cleanupOrphanTraces();
        if (activeTraces.size() <= MAX_ACTIVE_TRACES) {
            return;
        }
        long toRemove = Math.max(0, activeTraces.size() - MAX_ACTIVE_TRACES);
        if (toRemove <= 0) return;
        activeTraces.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(java.util.Comparator.comparing(TraceEntry::started)))
                .limit(toRemove)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(activeTraces::remove);
    }

    private record TraceEntry(Instant started, Timer.Sample sample) {}
}
