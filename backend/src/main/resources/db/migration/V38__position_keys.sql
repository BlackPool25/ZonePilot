-- V38__position_keys.sql
-- Strict position keys: every fix carries driver+tour+group (strict FK);
-- dedupe is tour-scoped UNIQUE(vehicle_id, tour_id, recorded_at).
-- Pre-tour rows backfill to a HISTORIC dummy tour (strict FK needs it).

ALTER TABLE vehicle_position_log
    ADD COLUMN IF NOT EXISTS driver_id BIGINT,
    ADD COLUMN IF NOT EXISTS tour_id BIGINT,
    ADD COLUMN IF NOT EXISTS group_id BIGINT;

DO $$
DECLARE
    hg BIGINT;
    hv BIGINT;
    hd BIGINT;
    ht BIGINT;
BEGIN
    SELECT id INTO hg FROM fleet_group WHERE name = 'HISTORIC' LIMIT 1;
    IF hg IS NULL THEN
        INSERT INTO fleet_group (name, is_active) VALUES ('HISTORIC', FALSE) RETURNING id INTO hg;
    END IF;

    SELECT id INTO hv FROM vehicle WHERE registration_number = 'HISTORIC' LIMIT 1;
    IF hv IS NULL THEN
        INSERT INTO vehicle (registration_number, vehicle_class, owner_name, is_active)
        VALUES ('HISTORIC', 'LCV', 'historic', FALSE) RETURNING id INTO hv;
    END IF;

    SELECT id INTO hd FROM driver WHERE licence_no = 'HISTORIC' LIMIT 1;
    IF hd IS NULL THEN
        INSERT INTO driver (name, licence_no, is_active)
        VALUES ('historic', 'HISTORIC', FALSE) RETURNING id INTO hd;
    END IF;

    SELECT id INTO ht FROM tour
    WHERE group_id = hg AND vehicle_id = hv AND driver_id = hd LIMIT 1;
    IF ht IS NULL THEN
        INSERT INTO tour (group_id, vehicle_id, driver_id, status)
        VALUES (hg, hv, hd, 'COMPLETED') RETURNING id INTO ht;
    END IF;

    UPDATE vehicle_position_log
       SET driver_id = hd, tour_id = ht, group_id = hg
     WHERE tour_id IS NULL OR driver_id IS NULL OR group_id IS NULL;
END $$;

ALTER TABLE vehicle_position_log ALTER COLUMN driver_id SET NOT NULL;
ALTER TABLE vehicle_position_log ALTER COLUMN tour_id SET NOT NULL;
ALTER TABLE vehicle_position_log ALTER COLUMN group_id SET NOT NULL;

ALTER TABLE vehicle_position_log
    ADD CONSTRAINT fk_position_driver FOREIGN KEY (driver_id) REFERENCES driver(id),
    ADD CONSTRAINT fk_position_tour FOREIGN KEY (tour_id) REFERENCES tour(id),
    ADD CONSTRAINT fk_position_group FOREIGN KEY (group_id) REFERENCES fleet_group(id);

DROP INDEX IF EXISTS uq_position_vehicle_recorded;
CREATE UNIQUE INDEX IF NOT EXISTS uq_position_vehicle_tour_recorded
    ON vehicle_position_log(vehicle_id, tour_id, recorded_at);
