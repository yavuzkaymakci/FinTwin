package com.fintwin.assistant.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window request limit per user and assistant (in memory; one backend instance). */
public final class RateLimiter {

    private final int requests;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> history = new ConcurrentHashMap<>();

    public RateLimiter(int requests, Duration window, Clock clock) {
        this.requests = requests;
        this.window = window;
        this.clock = clock;
    }

    /** Records a request and returns false if the key already used the window's quota. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Deque<Instant> calls = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && calls.peekFirst().isBefore(now.minus(window))) {
                calls.pollFirst();
            }
            if (calls.size() >= requests) {
                return false;
            }
            calls.addLast(now);
            return true;
        }
    }
}
