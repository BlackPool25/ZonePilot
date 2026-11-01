package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    void deleteByCreatedAtBefore(Instant cutoff);
}
