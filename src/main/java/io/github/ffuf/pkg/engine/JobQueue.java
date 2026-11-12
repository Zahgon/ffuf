package io.github.ffuf.pkg.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * The queue of jobs (the main job, sniper positions and recursion jobs) with the position of the active one. Every
 * access is synchronized: the workers push recursion jobs while the console lists or deletes them.
 */
final class JobQueue {
    private final List<Job.QueueJob> jobs = new ArrayList<>();
    private int pos;

    synchronized void push(Job.QueueJob j) {
        jobs.add(j);
    }

    synchronized boolean hasNext() {
        return pos < jobs.size();
    }

    /** Moves to the next job and returns it. */
    synchronized Job.QueueJob advance() {
        Job.QueueJob job = jobs.get(pos);
        pos++;
        return job;
    }

    /** The 1-based position of the active job, or 0 before the first one starts. */
    synchronized int position() {
        return pos;
    }

    synchronized int total() {
        return jobs.size();
    }

    /** The active job followed by the jobs still waiting, or an empty list before the first job starts. */
    synchronized List<Job.QueueJob> remaining() {
        int start = pos - 1;
        if (start < 0 || start > jobs.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(jobs.subList(start, jobs.size()));
    }

    /** Removes the job at offset from the active job (offset 0 is the active job). */
    synchronized boolean removeAt(int offset) {
        int idx = pos + offset - 1;
        if (idx < 0 || idx >= jobs.size()) {
            return false;
        }
        jobs.remove(idx);
        return true;
    }
}
