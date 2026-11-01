package com.zonepilot.backend.service;

/**
 * Authenticated worker identity for background jobs (replay, purge). The
 * ingest key-binding no longer silently defaults null callers: a null caller
 * is accepted only inside a worker block, which is trusted like an explicit
 * operator (explicit tour keys still required from the job itself).
 */
public final class IngestWorkerContext {

    private static final ThreadLocal<Boolean> WORKER =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private IngestWorkerContext() {}

    public static boolean isWorker() {
        return Boolean.TRUE.equals(WORKER.get());
    }

    public static <T> T runAsWorker(java.util.function.Supplier<T> job) {
        WORKER.set(Boolean.TRUE);
        try {
            return job.get();
        } finally {
            WORKER.remove();
        }
    }

    public static void runAsWorker(Runnable job) {
        runAsWorker(() -> {
            job.run();
            return null;
        });
    }
}
