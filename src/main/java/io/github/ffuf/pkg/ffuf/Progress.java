package io.github.ffuf.pkg.ffuf;

/**
 * A progress report of the running job: when it started (System.nanoTime), the requests sent and in total, the
 * current request rate, the position in the job queue and the error count.
 */
public record Progress(long startedAtNanos, int reqCount, long reqTotal, long reqSec, int queuePos, int queueTotal, int errorCount) {
}
