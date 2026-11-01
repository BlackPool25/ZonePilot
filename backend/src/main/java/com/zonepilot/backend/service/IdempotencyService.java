package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.IdempotencyRecord;
import com.zonepilot.backend.exception.ConflictException;
import com.zonepilot.backend.repository.IdempotencyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-supplied idempotency keys for PoD, duty, SOS, and position writes.
 * JPA-backed in prod (cross-instance); in-memory when constructed without a
 * repository (unit tests). Keys live 24h; duplicates throw 409.
 */
@Service
public class IdempotencyService {

    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final IdempotencyRepository repo;
    private final ConcurrentHashMap<String, Instant> memory = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(IdempotencyRepository repo) {
        this.repo = repo;
    }

    /** In-memory instance (unit tests, single-node fallback). */
    public IdempotencyService() {
        this(null);
    }

    /** Test hook: clear the in-memory key set. */
    public void reset() {
        memory.clear();
    }

    /**
     * Claims {@code opId}. Returns true on first claim; duplicate claims
     * throw {@link ConflictException} (409) so offline-queue redeliveries
     * never double-apply. A null/blank key is a no-op (legacy callers).
     */
    public boolean claim(String opId) {
        if (opId == null || opId.isBlank()) {
            return true;
        }
        if (repo == null) {
            Instant prev = memory.putIfAbsent(opId, Instant.now());
            if (prev != null) {
                throw new ConflictException("duplicate opId '" + opId + "'");
            }
            return true;
        }
        try {
            repo.deleteByCreatedAtBefore(Instant.now().minus(KEY_TTL));
        } catch (RuntimeException ignored) {
            // Purge is best-effort; the unique insert below is the real gate.
        }
        IdempotencyRecord row = new IdempotencyRecord();
        row.setOpId(opId);
        try {
            repo.saveAndFlush(row);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("duplicate opId '" + opId + "'");
        }
        return true;
    }
}
