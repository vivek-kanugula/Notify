package com.notify.sse.stream;

import org.springframework.stereotype.Component;

/**
 * Monotonic elapsed time for stream deadlines (/ , ).
 *
 * <p>Deliberately <b>not</b> the injected business {@code Clock}: a serving deadline must survive wall-clock steps,
 * NTP slews and timezone changes, so it is measured with {@link System#nanoTime()}. A seam rather than a direct call
 * so a test can advance time by hand instead of waiting.
 */
@Component
public class MonotonicTimeSource {

    public long nanos() {
        return System.nanoTime();
    }

    public long millis() {
        return nanos() / 1_000_000L;
    }
}