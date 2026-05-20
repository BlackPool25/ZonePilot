-- V1__schema.sql
-- Core schema: all tables with constraints, FKs, and partitioning

-- TABLE: depot
CREATE TABLE depot (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    address     TEXT NOT NULL,
    location    GEOMETRY(POINT, 4326) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TABLE: vehicle
CREATE TABLE vehicle (
    id                   BIGSERIAL PRIMARY KEY,
    registration_number  VARCHAR(20) NOT NULL UNIQUE,
    vehicle_class        VARCHAR(20) NOT NULL CHECK (vehicle_class IN ('TWO_WHEELER','LCV','HCV')),
    owner_name           VARCHAR(100) NOT NULL,
    depot_id             BIGINT NOT NULL REFERENCES depot(id) ON DELETE RESTRICT,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TABLE: zone_restriction
CREATE TABLE zone_restriction (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(150) NOT NULL,
    description      TEXT,
    boundary         GEOMETRY(POLYGON, 4326) NOT NULL,
    restriction_type VARCHAR(30) NOT NULL CHECK (restriction_type IN ('NO_ENTRY','TIME_RESTRICTED','VEHICLE_CLASS_RESTRICTED')),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TABLE: zone_restriction_rule
CREATE TABLE zone_restriction_rule (
    id                       BIGSERIAL PRIMARY KEY,
    zone_id                  BIGINT NOT NULL REFERENCES zone_restriction(id) ON DELETE CASCADE,
    applicable_vehicle_class VARCHAR(20) CHECK (applicable_vehicle_class IN ('TWO_WHEELER','LCV','HCV','ALL')),
    restriction_start_time   TIME,
    restriction_end_time     TIME,
    days_of_week_bitmask     SMALLINT NOT NULL DEFAULT 127,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE
);

-- TABLE: dispatch_route
CREATE TABLE dispatch_route (
    id                      BIGSERIAL PRIMARY KEY,
    vehicle_id              BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE RESTRICT,
    origin_point            GEOMETRY(POINT, 4326) NOT NULL,
    destination_point       GEOMETRY(POINT, 4326) NOT NULL,
    planned_route_geometry  GEOMETRY(LINESTRING, 4326),
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING','COMPLIANT','NON_COMPLIANT','ACTIVE','COMPLETED')),
    validation_timestamp    TIMESTAMPTZ,
    dispatched_at           TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TABLE: vehicle_position_log (PARTITIONED by RANGE on recorded_at)
CREATE TABLE vehicle_position_log (
    id           BIGSERIAL,
    vehicle_id   BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    position     GEOMETRY(POINT, 4326) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    speed_kmh    NUMERIC(5,2),
    heading_deg  SMALLINT,
    source       VARCHAR(10) NOT NULL DEFAULT 'LIVE'
                    CHECK (source IN ('LIVE','SIMULATED')),
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);

-- Monthly partitions
CREATE TABLE vehicle_position_log_2025_01
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE vehicle_position_log_2025_02
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

CREATE TABLE vehicle_position_log_2025_03
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-03-01') TO ('2025-04-01');

CREATE TABLE vehicle_position_log_2025_04
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-04-01') TO ('2025-05-01');

CREATE TABLE vehicle_position_log_2025_05
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-05-01') TO ('2025-06-01');

CREATE TABLE vehicle_position_log_2025_06
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-06-01') TO ('2025-07-01');

CREATE TABLE vehicle_position_log_default
    PARTITION OF vehicle_position_log DEFAULT;

-- TABLE: zone_breach_log
CREATE TABLE zone_breach_log (
    id                      BIGSERIAL PRIMARY KEY,
    vehicle_id              BIGINT NOT NULL REFERENCES vehicle(id),
    zone_id                 BIGINT NOT NULL REFERENCES zone_restriction(id),
    position_log_id         BIGINT NOT NULL,
    breach_time             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    breach_type             VARCHAR(30) NOT NULL
                               CHECK (breach_type IN ('NO_ENTRY','TIME_WINDOW','VEHICLE_CLASS')),
    rule_id                 BIGINT REFERENCES zone_restriction_rule(id),
    resolved_route_geometry GEOMETRY(LINESTRING, 4326),
    is_acknowledged         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TABLE: simulation_path
CREATE TABLE simulation_path (
    id                    BIGSERIAL PRIMARY KEY,
    vehicle_id            BIGINT NOT NULL REFERENCES vehicle(id),
    scenario_name         VARCHAR(100) NOT NULL,
    waypoints             GEOMETRY(LINESTRING, 4326) NOT NULL,
    current_step_index    INTEGER NOT NULL DEFAULT 0,
    total_steps           INTEGER NOT NULL,
    is_active             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
