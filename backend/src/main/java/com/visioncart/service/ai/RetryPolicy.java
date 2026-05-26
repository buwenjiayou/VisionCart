package com.visioncart.service.ai;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

public final class RetryPolicy {

    private RetryPolicy() {}

    public static boolean isRetryable(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException) {
                return true;
            }
            cause = cause.getCause();
        }
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("429")
                || lower.contains("rate limit")
                || lower.contains("500")
                || lower.contains("502")
                || lower.contains("503")
                || lower.contains("504")
                || lower.contains("timeout")
                || lower.contains("timed out");
    }

    public static long backoffDelayMs(int attempt, long baseDelayMs) {
        long exponential = baseDelayMs * (1L << Math.min(attempt, 10));
        long jitter = (long) (ThreadLocalRandom.current().nextDouble() * baseDelayMs * 0.5);
        return exponential + jitter;
    }

    public static <T> T executeWithRetry(Callable<T> action, int maxRetries, long baseDelayMs) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries && isRetryable(e)) {
                    try {
                        Thread.sleep(backoffDelayMs(attempt, baseDelayMs));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else if (!isRetryable(e)) {
                    throw e;
                }
            }
        }
        throw lastException;
    }
}
