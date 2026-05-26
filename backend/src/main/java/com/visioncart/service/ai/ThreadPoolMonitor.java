package com.visioncart.service.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Component
public class ThreadPoolMonitor {

    public ThreadPoolMonitor(
            @Qualifier("searchExecutor") ExecutorService searchExecutor,
            @Qualifier("recognitionExecutor") ExecutorService recognitionExecutor,
            MeterRegistry meterRegistry) {
        registerGauges("search", searchExecutor, meterRegistry);
        registerGauges("recognition", recognitionExecutor, meterRegistry);
    }

    private void registerGauges(String name, ExecutorService executor, MeterRegistry registry) {
        if (!(executor instanceof ThreadPoolExecutor pool)) return;

        Tags tags = Tags.of("pool", name);
        registry.gauge("threadpool.active", tags, pool, ThreadPoolExecutor::getActiveCount);
        registry.gauge("threadpool.pool.size", tags, pool, ThreadPoolExecutor::getPoolSize);
        registry.gauge("threadpool.core.size", tags, pool, ThreadPoolExecutor::getCorePoolSize);
        registry.gauge("threadpool.max.size", tags, pool, ThreadPoolExecutor::getMaximumPoolSize);
        registry.gauge("threadpool.queue.size", tags, pool, p -> p.getQueue().size());
        registry.gauge("threadpool.queue.remaining", tags, pool, p -> p.getQueue().remainingCapacity());
        registry.gauge("threadpool.completed", tags, pool, ThreadPoolExecutor::getCompletedTaskCount);
    }
}
