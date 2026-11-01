package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.OsmImportMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OsmImportMetaRepository extends JpaRepository<OsmImportMeta, Long> {

    Optional<OsmImportMeta> findFirstByOrderByImportedAtDesc();
}
