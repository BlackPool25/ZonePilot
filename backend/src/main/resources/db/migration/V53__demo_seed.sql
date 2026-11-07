-- V53__demo_seed.sql
-- One-command demo dataset covering every feature: users with known
-- passwords, a group + fleet + drivers, an ACTIVE tour driving into a
-- real seeded zone (open breach), a PLANNED tour, and a PENDING_APPROVAL
-- tour with PoD evidence. Idempotent: safe to replay; skips tour/position
-- seeding when the demo group already has tours.
--
-- Demo logins (password for all: demo1234):
--   demo.owner@zonepilot.io / OWNER        (full admin view)
--   demo.disp@zonepilot.io  / DISPATCHER   (manager flow, owns demo group)
--   demo.driver1@zonepilot.io / DRIVER     (active tour + breach)
--   demo.driver2@zonepilot.io / DRIVER     (planned tour)

DO $$
DECLARE
    v_owner      BIGINT;
    v_disp       BIGINT;
    v_drv1u      BIGINT;
    v_drv2u      BIGINT;
    v_drv1       BIGINT;
    v_drv2       BIGINT;
    v_group      BIGINT;
    v_tour_a     BIGINT;
    v_tour_b     BIGINT;
    v_tour_c     BIGINT;
    v_zone_mg    BIGINT;
    v_zone_sb    BIGINT;
    v_veh4       BIGINT;
    v_veh5       BIGINT;
    v_veh7       BIGINT;
    v_pos_in     BIGINT;
    v_pos_sb     BIGINT;
    v_hash       TEXT := '$2b$10$wLG8CTi2mwKw/WP6tLLkLOUeSwl/Nem76tWZ9yfFR8hdnuVXPHGG2'; -- demo1234
BEGIN
    -- Users (role CHECK allows OWNER/DISPATCHER/DRIVER only).
    INSERT INTO app_user (email, password_hash, role, is_active)
    VALUES ('demo.owner@zonepilot.io', v_hash, 'OWNER', TRUE)
    ON CONFLICT (email) DO NOTHING;
    INSERT INTO app_user (email, password_hash, role, is_active)
    VALUES ('demo.disp@zonepilot.io', v_hash, 'DISPATCHER', TRUE)
    ON CONFLICT (email) DO NOTHING;
    INSERT INTO app_user (email, password_hash, role, is_active)
    VALUES ('demo.driver1@zonepilot.io', v_hash, 'DRIVER', TRUE)
    ON CONFLICT (email) DO NOTHING;
    INSERT INTO app_user (email, password_hash, role, is_active)
    VALUES ('demo.driver2@zonepilot.io', v_hash, 'DRIVER', TRUE)
    ON CONFLICT (email) DO NOTHING;

    SELECT id INTO v_owner FROM app_user WHERE email = 'demo.owner@zonepilot.io';
    SELECT id INTO v_disp  FROM app_user WHERE email = 'demo.disp@zonepilot.io';
    SELECT id INTO v_drv1u FROM app_user WHERE email = 'demo.driver1@zonepilot.io';
    SELECT id INTO v_drv2u FROM app_user WHERE email = 'demo.driver2@zonepilot.io';

    -- Driver profiles linked to logins.
    INSERT INTO driver (user_id, name, phone, licence_no, licence_expiry, salary_mode, is_active)
    VALUES (v_drv1u, 'Demo Driver One', '+91-98000-00001', 'DEMO-LIC-001', CURRENT_DATE + 365, 'PER_TRIP', TRUE)
    ON CONFLICT (licence_no) DO NOTHING;
    INSERT INTO driver (user_id, name, phone, licence_no, licence_expiry, salary_mode, is_active)
    VALUES (v_drv2u, 'Demo Driver Two', '+91-98000-00002', 'DEMO-LIC-002', CURRENT_DATE + 365, 'PER_TRIP', TRUE)
    ON CONFLICT (licence_no) DO NOTHING;

    SELECT id INTO v_drv1 FROM driver WHERE licence_no = 'DEMO-LIC-001';
    SELECT id INTO v_drv2 FROM driver WHERE licence_no = 'DEMO-LIC-002';

    -- Reuse the V4 seed fleet + V5 zones by natural keys (ids vary per DB).
    SELECT id INTO v_veh4 FROM vehicle WHERE registration_number = 'KA01-LCV-0004';
    SELECT id INTO v_veh5 FROM vehicle WHERE registration_number = 'KA01-LCV-0005';
    SELECT id INTO v_veh7 FROM vehicle WHERE registration_number = 'KA01-HCV-0007';
    SELECT id INTO v_zone_mg FROM zone_restriction WHERE name = 'MG Road No-Entry';
    SELECT id INTO v_zone_sb FROM zone_restriction WHERE name = 'Silk Board Freight Restriction';

    IF v_veh4 IS NULL OR v_veh7 IS NULL OR v_zone_mg IS NULL THEN
        RAISE NOTICE 'V53 demo seed skipped: base V4/V5 seeds not present';
        RETURN;
    END IF;

    -- Demo group owned by the dispatcher.
    SELECT id INTO v_group FROM fleet_group WHERE name = 'HSR Demo Fleet';
    IF v_group IS NULL THEN
        INSERT INTO fleet_group (name, manager_id, is_active)
        VALUES ('HSR Demo Fleet', v_disp, TRUE)
        RETURNING id INTO v_group;
    ELSE
        UPDATE fleet_group SET manager_id = v_disp WHERE id = v_group;
    END IF;

    INSERT INTO group_vehicle (group_id, vehicle_id)
    VALUES (v_group, v_veh4), (v_group, v_veh5), (v_group, v_veh7)
    ON CONFLICT (group_id, vehicle_id) DO NOTHING;
    INSERT INTO group_driver (group_id, driver_id)
    VALUES (v_group, v_drv1), (v_group, v_drv2)
    ON CONFLICT (group_id, driver_id) DO NOTHING;

    -- Stop here on replay when demo tours already exist (keep user edits).
    IF EXISTS (SELECT 1 FROM tour WHERE group_id = v_group) THEN
        RAISE NOTICE 'V53 demo seed: demo tours already present, users ensured';
        RETURN;
    END IF;

    -- Tour A: ACTIVE HCV run HSR -> MG Road (inside zone) -> Indiranagar.
    INSERT INTO tour (group_id, vehicle_id, driver_id, status, duty_status, created_by_user_id)
    VALUES (v_group, v_veh7, v_drv1, 'ACTIVE', 'DRIVING', v_disp)
    RETURNING id INTO v_tour_a;
    INSERT INTO tour_stop (tour_id, seq, point, address, quantity, weight_kg, recipient_name, status)
    VALUES
        (v_tour_a, 0, ST_SetSRID(ST_MakePoint(77.6300, 12.9110), 4326), 'HSR Layout Depot', NULL, NULL, NULL, 'DELIVERED'),
        (v_tour_a, 1, ST_SetSRID(ST_MakePoint(77.6200, 12.9740), 4326), 'MG Road Drop #1', 12, 240.5, 'Demo Retail Stores', 'PENDING'),
        (v_tour_a, 2, ST_SetSRID(ST_MakePoint(77.6400, 12.9780), 4326), 'Indiranagar Drop #2', 8, 120.0, 'Demo Mart', 'PENDING');
    UPDATE tour_stop SET delivered_at = NOW() - INTERVAL '3 hours',
        pod_lat = 12.9110, pod_lng = 77.6300
        WHERE tour_id = v_tour_a AND seq = 0;

    -- Positions: west (outside) -> inside MG Road -> east (outside).
    INSERT INTO vehicle_position_log
        (vehicle_id, driver_id, tour_id, group_id, position, recorded_at, speed_kmh, heading_deg, source, is_replay)
    VALUES (v_veh7, v_drv1, v_tour_a, v_group,
        ST_SetSRID(ST_MakePoint(77.6100, 12.9740), 4326),
        NOW() - INTERVAL '3 hours', 38, 90, 'LIVE', FALSE);
    INSERT INTO vehicle_position_log
        (vehicle_id, driver_id, tour_id, group_id, position, recorded_at, speed_kmh, heading_deg, source, is_replay)
    VALUES (v_veh7, v_drv1, v_tour_a, v_group,
        ST_SetSRID(ST_MakePoint(77.6200, 12.9740), 4326),
        NOW() - INTERVAL '2 hours', 22, 90, 'LIVE', FALSE)
    RETURNING id INTO v_pos_in;
    INSERT INTO vehicle_position_log
        (vehicle_id, driver_id, tour_id, group_id, position, recorded_at, speed_kmh, heading_deg, source, is_replay)
    VALUES (v_veh7, v_drv1, v_tour_a, v_group,
        ST_SetSRID(ST_MakePoint(77.6300, 12.9740), 4326),
        NOW() - INTERVAL '1 hour', 45, 90, 'LIVE', FALSE);

    -- The live demo trigger: one OPEN breach inside MG Road.
    INSERT INTO zone_breach_log
        (vehicle_id, zone_id, position_log_id, breach_time, end_time, distance_m,
         breach_type, rule_id, is_acknowledged)
    VALUES (v_veh7, v_zone_mg, v_pos_in,
        NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours', 0.0,
        'TIME_WINDOW', NULL, FALSE);

    -- A second, older, acknowledged breach (Silk Board) for the triage view.
    IF v_zone_sb IS NOT NULL AND v_veh5 IS NOT NULL THEN
        INSERT INTO vehicle_position_log
            (vehicle_id, driver_id, tour_id, group_id, position, recorded_at, speed_kmh, heading_deg, source, is_replay)
        VALUES (v_veh5, v_drv2, v_tour_a, v_group,
            ST_SetSRID(ST_MakePoint(77.6160, 12.8445), 4326),
            NOW() - INTERVAL '26 hours', 18, 180, 'LIVE', FALSE)
        RETURNING id INTO v_pos_sb;
        INSERT INTO zone_breach_log
            (vehicle_id, zone_id, position_log_id, breach_time, end_time, distance_m,
             breach_type, rule_id, is_acknowledged)
        VALUES (v_veh5, v_zone_sb, v_pos_sb,
            NOW() - INTERVAL '26 hours', NOW() - INTERVAL '25 hours', 140.0,
            'VEHICLE_CLASS', NULL, TRUE);
    END IF;

    -- Duty trail for the active tour.
    INSERT INTO duty_log (tour_id, driver_id, duty_status, recorded_at)
    VALUES
        (v_tour_a, v_drv1, 'ON_DUTY', NOW() - INTERVAL '3 hours'),
        (v_tour_a, v_drv1, 'DRIVING', NOW() - INTERVAL '2 hours 55 minutes');

    -- Tour B: PLANNED LCV run, no positions (unassigned-triage state).
    INSERT INTO tour (group_id, vehicle_id, driver_id, status, duty_status, created_by_user_id)
    VALUES (v_group, v_veh4, v_drv2, 'PLANNED', 'OFF_DUTY', v_disp)
    RETURNING id INTO v_tour_b;
    INSERT INTO tour_stop (tour_id, seq, point, address, quantity, weight_kg, recipient_name, status)
    VALUES
        (v_tour_b, 0, ST_SetSRID(ST_MakePoint(77.6389, 12.9116), 4326), 'HSR Layout Depot', NULL, NULL, NULL, 'PENDING'),
        (v_tour_b, 1, ST_SetSRID(ST_MakePoint(77.6245, 12.9352), 4326), 'Koramangala Drop', 5, 60.0, 'Demo Foods', 'PENDING');

    -- Tour C: PENDING_APPROVAL with PoD evidence (manager approve flow).
    INSERT INTO tour (group_id, vehicle_id, driver_id, status, duty_status, created_by_user_id)
    VALUES (v_group, v_veh5, v_drv2, 'PENDING_APPROVAL', 'ON_DUTY', v_disp)
    RETURNING id INTO v_tour_c;
    INSERT INTO tour_stop (tour_id, seq, point, address, quantity, weight_kg, recipient_name, status,
        delivered_at, pod_lat, pod_lng, qty_delivered)
    VALUES
        (v_tour_c, 0, ST_SetSRID(ST_MakePoint(77.6389, 12.9116), 4326), 'HSR Layout Depot',
         NULL, NULL, NULL, 'DELIVERED', NOW() - INTERVAL '5 hours', 12.9116, 77.6389, NULL),
        (v_tour_c, 1, ST_SetSRID(ST_MakePoint(77.6040, 12.9400), 4326), 'Richmond Town Drop',
         6, 90.0, 'Demo Pharma', 'DELIVERED', NOW() - INTERVAL '4 hours', 12.9400, 77.6040, 6);
    INSERT INTO duty_log (tour_id, driver_id, duty_status, recorded_at)
    VALUES
        (v_tour_c, v_drv2, 'ON_DUTY', NOW() - INTERVAL '5 hours'),
        (v_tour_c, v_drv2, 'DRIVING', NOW() - INTERVAL '4 hours 50 minutes'),
        (v_tour_c, v_drv2, 'ON_DUTY', NOW() - INTERVAL '4 hours');

    -- Availability mirrors the tour states.
    UPDATE vehicle SET availability = 'IN_TRANSIT' WHERE id = v_veh7;
    UPDATE vehicle SET availability = 'RESERVED'   WHERE id = v_veh4;
    UPDATE vehicle SET availability = 'IN_TRANSIT' WHERE id = v_veh5;
END $$;
