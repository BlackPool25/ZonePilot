-- V32__fleet_groups.sql
-- Flat fleet groups with M2M vehicle + driver membership (slice 1: groups + dispatch).

CREATE TABLE fleet_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    manager_id  BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fleet_group_manager ON fleet_group(manager_id);

CREATE TABLE group_vehicle (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT NOT NULL REFERENCES fleet_group(id) ON DELETE CASCADE,
    vehicle_id  BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_group_vehicle UNIQUE (group_id, vehicle_id)
);
CREATE INDEX idx_group_vehicle_vehicle ON group_vehicle(vehicle_id);

CREATE TABLE group_driver (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT NOT NULL REFERENCES fleet_group(id) ON DELETE CASCADE,
    driver_id   BIGINT NOT NULL REFERENCES driver(id) ON DELETE CASCADE,
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_group_driver UNIQUE (group_id, driver_id)
);
CREATE INDEX idx_group_driver_driver ON group_driver(driver_id);
