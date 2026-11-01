package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * OSM import bookkeeping: which extract was loaded, when, and what changed.
 * The import scripts upsert one row per import run; the ops endpoint reads
 * the latest for the "map version" dashboard.
 */
@Entity
@Table(name = "osm_import_meta")
public class OsmImportMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extract_name", nullable = false, length = 200)
    private String extractName;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "nodes_count")
    private Long nodesCount;

    @Column(name = "ways_count")
    private Long waysCount;

    @Column(columnDefinition = "TEXT")
    private String note;

    public Long getId() { return id; }
    public String getExtractName() { return extractName; }
    public void setExtractName(String extractName) { this.extractName = extractName; }
    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }
    public Long getNodesCount() { return nodesCount; }
    public void setNodesCount(Long nodesCount) { this.nodesCount = nodesCount; }
    public Long getWaysCount() { return waysCount; }
    public void setWaysCount(Long waysCount) { this.waysCount = waysCount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
