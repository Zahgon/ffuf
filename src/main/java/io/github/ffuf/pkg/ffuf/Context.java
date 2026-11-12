package io.github.ffuf.pkg.ffuf;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The cancellation context of a run (Go's context.WithCancel): stopping the run cancels it, which closes the
 * connections of the requests in flight and ends a pending -p delay at once.
 */
public final class Context {
    private final Set<Closeable> open = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean done;

    /** Creates a context that is not cancelled. */
    public Context() {
        // Fields start at their declared defaults.
    }

    /** Cancels the context; the first call closes every registered connection. */
    public void cancel() {
        Closeable[] toClose;
        synchronized (this) {
            if (done) {
                return;
            }
            done = true;
            notifyAll();
            toClose = open.toArray(new Closeable[0]);
            open.clear();
        }
        for (Closeable c : toClose) {
            try {
                c.close();
            } catch (IOException e) {
                // closing aborts the request; an error closing it changes nothing
            }
        }
    }

    /** {@return true once the context is cancelled} */
    public synchronized boolean isDone() {
        return done;
    }

    /**
     * Registers a connection to close on cancel.
     *
     * @return true when registered, false (without registering) when the context is already cancelled
     */
    public synchronized boolean register(Closeable c) {
        if (done) {
            return false;
        }
        open.add(c);
        return true;
    }

    /** Removes a connection registered with register. */
    public synchronized void unregister(Closeable c) {
        open.remove(c);
    }

    /** Sleeps for nanos, returning early when the context is cancelled. */
    public void sleep(long nanos) {
        if (nanos <= 0) {
            return;
        }
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            long left = nanos;
            while (!done && left > 0) {
                try {
                    wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                left = deadline - System.nanoTime();
            }
        }
    }
}
