-- V6__seed_zone_rules.sql
-- Rules per zone: vehicle class, time window, days of week

-- Rule for Zone 1: MG Road No-Entry — HCV restricted 07:00–11:00 and 21:00–23:00
INSERT INTO zone_restriction_rule (zone_id, applicable_vehicle_class, restriction_start_time, restriction_end_time, days_of_week_bitmask, is_active) VALUES
(1, 'HCV', '07:00', '11:00', 127, TRUE),
(1, 'HCV', '21:00', '23:00', 127, TRUE);

-- Rule for Zone 2: Silk Board — HCV restricted 08:00–22:00
INSERT INTO zone_restriction_rule (zone_id, applicable_vehicle_class, restriction_start_time, restriction_end_time, days_of_week_bitmask, is_active) VALUES
(2, 'HCV', '08:00', '22:00', 127, TRUE);

-- Rule for Zone 3: Majestic — ALL vehicles, 24/7 (NULL times = always)
INSERT INTO zone_restriction_rule (zone_id, applicable_vehicle_class, restriction_start_time, restriction_end_time, days_of_week_bitmask, is_active) VALUES
(3, 'ALL', NULL, NULL, 127, TRUE);

-- Rule for Zone 4: Koramangala — LCV and HCV restricted 07:00–10:00
INSERT INTO zone_restriction_rule (zone_id, applicable_vehicle_class, restriction_start_time, restriction_end_time, days_of_week_bitmask, is_active) VALUES
(4, 'LCV', '07:00', '10:00', 127, TRUE),
(4, 'HCV', '07:00', '10:00', 127, TRUE);

-- Rule for Zone 5: Electronic City — HCV restricted 06:00–09:00 and 18:00–21:00
INSERT INTO zone_restriction_rule (zone_id, applicable_vehicle_class, restriction_start_time, restriction_end_time, days_of_week_bitmask, is_active) VALUES
(5, 'HCV', '06:00', '09:00', 127, TRUE),
(5, 'HCV', '18:00', '21:00', 127, TRUE);
