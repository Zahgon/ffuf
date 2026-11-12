package io.github.ffuf.pkg.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.ffuf.pkg.ffuf.Config;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RatethrottleTest {
    private static Config config(long rate) {
        Config conf = new Config();
        conf.rate = rate;
        conf.threads = 40;
        return conf;
    }

    /** A rate above one million per second divides to a zero period; it gets the one microsecond floor instead. */
    @Test
    void testNewRateThrottle_HugeRateNoPanic() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (long rate : new long[] {2_000_000, 1_000_001}) {
                RateThrottle r = new RateThrottle(config(rate));
                for (int i = 0; i < 1000; i++) {
                    r.take();
                }
            }
        });
    }

    @Test
    void testChangeRate_HugeRateNoPanic() {
        Config conf = config(0);
        RateThrottle r = new RateThrottle(conf);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int rate : new int[] {2_000_000, 1_000_001, 0, -5, 500}) {
                r.changeRate(rate);
                r.take();
            }
        });
        assertEquals(500, conf.rate, "configured rate after the last change");
    }
}
