package com.visioncart.service.ai;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    @Test
    void socketTimeoutIsRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException(new SocketTimeoutException()))).isTrue();
    }

    @Test
    void connectExceptionIsRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException(new ConnectException()))).isTrue();
    }

    @Test
    void rateLimitIsRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException("429 Too Many Requests"))).isTrue();
    }

    @Test
    void serverErrorIsRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException("503 Service Unavailable"))).isTrue();
    }

    @Test
    void timeoutMessageIsRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException("Connection timed out"))).isTrue();
    }

    @Test
    void authErrorIsNotRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException("401 Unauthorized"))).isFalse();
    }

    @Test
    void badRequestIsNotRetryable() {
        assertThat(RetryPolicy.isRetryable(new RuntimeException("400 Bad Request"))).isFalse();
    }

    @Test
    void backoffIncreasesExponentially() {
        long delay0 = RetryPolicy.backoffDelayMs(0, 1000);
        long delay1 = RetryPolicy.backoffDelayMs(1, 1000);
        long delay2 = RetryPolicy.backoffDelayMs(2, 1000);

        assertThat(delay0).isBetween(1000L, 1500L);
        assertThat(delay1).isBetween(2000L, 2500L);
        assertThat(delay2).isBetween(4000L, 4500L);
    }

    @Test
    void executeWithRetrySucceedsOnSecondAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryPolicy.executeWithRetry(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("503 Service Unavailable");
            }
            return "success";
        }, 2, 100L);

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void executeWithRetryGivesUpAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("500 Internal Server Error");
        }, 2, 100L)).isInstanceOf(RuntimeException.class);

        assertThat(attempts.get()).isEqualTo(3); // initial + 2 retries
    }

    @Test
    void nonRetryableExceptionFailsImmediately() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("401 Unauthorized");
        }, 3, 100L)).isInstanceOf(RuntimeException.class);

        assertThat(attempts.get()).isEqualTo(1); // no retries
    }
}
