package io.github.ffuf.pkg.engine;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.output.Stdoutput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PausegateTest {
    /** An output that discards everything. */
    static final class NullOutput extends Stdoutput {
        NullOutput(Config conf) {
            super(conf);
        }

        @Override
        public void info(String s) {
        }

        @Override
        public void warning(String s) {
        }

        @Override
        public void error(String s) {
        }
    }

    /** A running job with a null output and its own cancellation context. */
    private static Job runningJob() {
        Config conf = new Config();
        Job j = new Job(conf, null, null, new NullOutput(conf));
        j.setRunning(true);
        return j;
    }

    /** True when a checkpoint passed on another thread returns within two seconds. */
    private static boolean checkpointPasses(Job j) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            j.pauseCheckpoint();
            done.countDown();
        });
        t.setDaemon(true);
        t.start();
        return done.await(2, TimeUnit.SECONDS);
    }

    /** Duplicate and stray pause and resume calls keep the gate balanced while workers hammer the checkpoint. */
    @Test
    void testPauseGate_ConcurrentIdempotent() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            Job j = runningJob();
            j.pause();
            j.pause();
            j.resume();
            j.resume();
            j.resume();

            AtomicBoolean stop = new AtomicBoolean();
            List<Thread> workers = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                Thread t = new Thread(() -> {
                    while (!stop.get()) {
                        j.pauseCheckpoint();
                    }
                });
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
            try {
                for (int k = 0; k < 500; k++) {
                    j.pause();
                    j.resume();
                }
            } finally {
                stop.set(true);
                j.resume();
            }
            for (Thread t : workers) {
                t.join();
            }
        });
    }

    /** Pause and resume from two threads at once (the console and the interrupt handler) never unbalance the gate. */
    @Test
    void testPauseGate_ConcurrentPauseResume() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            Job j = runningJob();
            Thread pauser = new Thread(() -> {
                for (int i = 0; i < 100000; i++) {
                    j.pause();
                }
            });
            Thread resumer = new Thread(() -> {
                for (int i = 0; i < 100000; i++) {
                    j.resume();
                }
            });
            pauser.setDaemon(true);
            resumer.setDaemon(true);
            pauser.start();
            resumer.start();
            pauser.join();
            resumer.join();
            j.resume();
            assertTrue(checkpointPasses(j), "gate left closed after the last resume");
        });
    }

    /** A paused job that is stopped reopens the gate, so a worker reaching the checkpoint is not stranded. */
    @Test
    void testPauseGate_StopReopensGate() throws InterruptedException {
        Job j = runningJob();
        j.pause();
        j.stop();
        assertTrue(checkpointPasses(j), "pauseCheckpoint stranded after stop: the gate was not reopened");
    }

    /** Whatever the interleaving of a pause and a stop, the gate ends open. */
    @Test
    void testPauseGate_ConcurrentStopPauseNeverStrands() throws InterruptedException {
        for (int iter = 0; iter < 300; iter++) {
            Job j = runningJob();
            Thread a = new Thread(j::pause);
            Thread b = new Thread(j::stop);
            a.setDaemon(true);
            b.setDaemon(true);
            a.start();
            b.start();
            a.join();
            b.join();
            assertTrue(checkpointPasses(j), "iteration " + iter + ": worker stranded at checkpoint after concurrent stop/pause");
        }
    }
}
