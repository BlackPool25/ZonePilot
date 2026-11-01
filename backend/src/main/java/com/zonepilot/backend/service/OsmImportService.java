package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.OsmImportMeta;
import com.zonepilot.backend.repository.OsmImportMetaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * OSM import bookkeeping: the import scripts record which extract was loaded
 * and when, so the ops endpoint can show the map version behind routing.
 */
@Service
public class OsmImportService {

    private final OsmImportMetaRepository imports;

    public OsmImportService(OsmImportMetaRepository imports) {
        this.imports = imports;
    }

    public Optional<OsmImportMeta> latest() {
        return imports.findFirstByOrderByImportedAtDesc();
    }

    @Transactional
    public OsmImportMeta record(String extractName, Long nodes, Long ways, String note) {
        OsmImportMeta meta = new OsmImportMeta();
        meta.setExtractName(extractName);
        meta.setImportedAt(Instant.now());
        meta.setNodesCount(nodes);
        meta.setWaysCount(ways);
        meta.setNote(note);
        return imports.save(meta);
    }
}
