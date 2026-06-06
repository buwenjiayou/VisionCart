package com.visioncart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Wraps a Runnable to propagate MDC context from the calling thread to the async thread.
     */
    private Runnable mdcAware(Runnable r) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }
            try {
                r.run();
            } finally {
                MDC.clear();
            }
        };
    }

    private ThreadFactory loggingThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(mdcAware(r), prefix + counter.incrementAndGet());
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("Uncaught exception in thread {}", thread.getName(), ex));
            t.setDaemon(true);
            return t;
        };
    }

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ExecutorService searchExecutor(VisionCartProperties properties) {
        VisionCartProperties.Search search = properties.getSearch();
        return new ThreadPoolExecutor(
                search.getCorePoolSize(),
                search.getMaxPoolSize(),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(search.getQueueCapacity()),
                loggingThreadFactory("search-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "platformSearchExecutor", destroyMethod = "shutdown")
    public ExecutorService platformSearchExecutor(VisionCartProperties properties) {
        VisionCartProperties.Search search = properties.getSearch();
        int poolSize = Math.max(2, Math.min(8, search.getCorePoolSize()));
        int queueCapacity = Math.max(16, search.getQueueCapacity() / 4);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                loggingThreadFactory("platform-search-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "recognitionExecutor", destroyMethod = "shutdown")
    public ExecutorService recognitionExecutor(VisionCartProperties properties) {
        VisionCartProperties.Recognition recognition = properties.getRecognition();
        return new ThreadPoolExecutor(
                recognition.getCorePoolSize(),
                recognition.getMaxPoolSize(),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(recognition.getQueueCapacity()),
                loggingThreadFactory("recognition-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 专用线程池：平台商品详情/推广链接抓取。
     * 与 platformSearchExecutor（平台搜索）隔离，避免详情抓取阻塞搜索主链路。
     */
    @Bean(name = "detailExecutor", destroyMethod = "shutdown")
    public ExecutorService detailExecutor(VisionCartProperties properties) {
        VisionCartProperties.Search search = properties.getSearch();
        int poolSize = Math.max(2, Math.min(6, search.getCorePoolSize()));
        int queueCapacity = Math.max(16, search.getQueueCapacity() / 4);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                loggingThreadFactory("detail-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 专用线程池：AI 导购卡生成。
     * 与 platformSearchExecutor（平台搜索）隔离，避免 AI 推理阻塞搜索主链路。
     */
    @Bean(name = "aiSuggestionExecutor", destroyMethod = "shutdown")
    public ExecutorService aiSuggestionExecutor() {
        return new ThreadPoolExecutor(
                2, 4,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                loggingThreadFactory("ai-suggestion-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "mailExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("mail-");
        executor.setTaskDecorator(this::mdcAware);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
