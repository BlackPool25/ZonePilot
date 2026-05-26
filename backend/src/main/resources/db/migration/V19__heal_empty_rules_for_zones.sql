-- V19__heal_empty_rules_for_zones.sql
-- Automatically insert a default 24/7 rule for any zone restriction that does not currently have any rules,
-- to ensure they are evaluated correctly by routing and breach detection engines.

INSERT INTO zone_restriction_rule (
    zone_id,
    applicable_vehicle_class,
    days_of_week_bitmask,
    restriction_start_time,
    restriction_end_time,
    is_active
)
SELECT 
    zr.id AS zone_id,
    NULL::VARCHAR AS applicable_vehicle_class,
    127::SMALLINT AS days_of_week_bitmask,
    NULL::TIME AS restriction_start_time,
    NULL::TIME AS restriction_end_time,
    TRUE AS is_active
FROM zone_restriction zr
LEFT JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
WHERE zrr.id IS NULL;
