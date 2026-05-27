package com.visioncart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AsyncConfig {

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ExecutorService searchExecutor(VisionCartProperties properties) {
        VisionCartProperties.Search search = properties.getSearch();
        return new ThreadPoolExecutor(
                search.getCorePoolSize(),
                search.getMaxPoolSize(),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(search.getQueueCapacity()),
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
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
