package io.github.ffuf.pkg.engine;

import io.github.ffuf.pkg.ffuf.Config;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The -rate limiter: hands out one request slot per period, like a Go time.Ticker. A missed slot is dropped
 * rather than saved up, so a slow phase is never followed by a burst. Without a rate the period is one microsecond.
 * It also keeps the completion times of the last requests (five per thread, or five per second of the rate), from
 * which the progress line reports the current request rate.
 */
public class RateThrottle {
    private final Config config;
    private long periodNanos;
    private long nextSlot = System.nanoTime();
    private long[] rateCounter;
    private int ringPos;

    /** Creates the limiter for the configuration's -rate value. */
    public RateThrottle(Config conf) {
        this.config = conf;
        this.periodNanos = periodNanos(conf.rate);
        this.rateCounter = newCounter(conf.rate > 0 ? conf.rate * 5 : conf.threads * 5);
    }

    /** A ring of completion times in microseconds since the epoch; 0 marks a slot not yet used. */
    private static long[] newCounter(long size) {
        return new long[(int) Math.max(1, Math.min(size, Integer.MAX_VALUE - 8))];
    }

    /** {@return the requests per second over the recorded completion times, or 0 while they span a millisecond or less} */
    public synchronized long currentRate() {
        int n = 0;
        long lowest = 0;
        long highest = 0;
        for (long val : rateCounter) {
            if (val == 0) {
                continue;
            }
            n++;
            if (lowest == 0 || val < lowest) {
                lowest = val;
            }
            if (val > highest) {
                highest = val;
            }
        }
        long elapsedMillis = (highest - lowest) / 1000;
        if (n > 0 && elapsedMillis > 1) {
            return 1000L * n / elapsedMillis;
        }
        return 0;
    }

    /** {@return the configured rate, for display} */
    public synchronized long currentConfiguredRate() {
        return config.rate;
    }

    /** Records the completion of a request. */
    public synchronized void tick(Instant end) {
        ringPos = (ringPos + 1) % rateCounter.length;
        rateCounter[ringPos] = ChronoUnit.MICROS.between(Instant.EPOCH, end);
    }

    /** One million requests per second is the upper limit: a rate above it gets the one microsecond floor. */
    private static long periodNanos(long rate) {
        if (rate <= 0) {
            return 1_000L;
        }
        return Math.max(1L, 1_000_000L / rate) * 1_000L;
    }

    /** Sets a new rate (0 for none) and restarts the rate counter. */
    public synchronized void changeRate(long rate) {
        periodNanos = periodNanos(rate);
        rateCounter = newCounter(rate > 0 ? rate * 5 : config.threads * 5);
        ringPos = 0;
        config.rate = rate;
    }

    /** Blocks until the next request slot. */
    public void take() {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(nextSlot, now);
            nextSlot = slot + periodNanos;
            wait = slot - now;
        }
        while (wait > 0) {
            long start = System.nanoTime();
            LockSupport.parkNanos(wait);
            wait -= System.nanoTime() - start;
        }
    }
}
