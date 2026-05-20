-- V5__seed_zones.sql
-- 5 zone polygons for Bangalore restricted areas
-- Coordinates drawn based on BTP restriction zones

-- Zone 1: MG Road No-Entry (TIME_RESTRICTED for HCV)
INSERT INTO zone_restriction (name, description, boundary, restriction_type, is_active) VALUES
('MG Road No-Entry', 'MG Road corridor restricted for HCV during peak hours',
 ST_GeomFromText('POLYGON((77.6170 12.9760, 77.6230 12.9760, 77.6230 12.9720, 77.6170 12.9720, 77.6170 12.9760))', 4326),
 'TIME_RESTRICTED', TRUE);

-- Zone 2: Silk Board Freight Restriction (VEHICLE_CLASS_RESTRICTED for HCV)
INSERT INTO zone_restriction (name, description, boundary, restriction_type, is_active) VALUES
('Silk Board Freight Restriction', 'Silk Board junction area restricted for heavy vehicles',
 ST_GeomFromText('POLYGON((77.6130 12.8470, 77.6200 12.8470, 77.6200 12.8420, 77.6130 12.8420, 77.6130 12.8470))', 4326),
 'VEHICLE_CLASS_RESTRICTED', TRUE);

-- Zone 3: Majestic Bus Terminal Perimeter (NO_ENTRY for ALL)
INSERT INTO zone_restriction (name, description, boundary, restriction_type, is_active) VALUES
('Majestic Bus Terminal Perimeter', 'KBS/Majestic area no-entry zone for all commercial vehicles',
 ST_GeomFromText('POLYGON((77.5630 12.9800, 77.5690 12.9800, 77.5690 12.9760, 77.5630 12.9760, 77.5630 12.9800))', 4326),
 'NO_ENTRY', TRUE);

-- Zone 4: Koramangala Inner Zone (TIME_RESTRICTED for LCV, HCV)
INSERT INTO zone_restriction (name, description, boundary, restriction_type, is_active) VALUES
('Koramangala Inner Zone', 'Inner Koramangala residential area time-restricted zone',
 ST_GeomFromText('POLYGON((77.6200 12.9370, 77.6280 12.9370, 77.6280 12.9310, 77.6200 12.9310, 77.6200 12.9370))', 4326),
 'TIME_RESTRICTED', TRUE);

-- Zone 5: Electronic City Phase 1 Entry (VEHICLE_CLASS_RESTRICTED for HCV)
INSERT INTO zone_restriction (name, description, boundary, restriction_type, is_active) VALUES
('Electronic City Phase 1 Entry', 'EC Phase 1 gate area restricted for HCV during peak hours',
 ST_GeomFromText('POLYGON((77.6700 12.8450, 77.6780 12.8450, 77.6780 12.8390, 77.6700 12.8390, 77.6700 12.8450))', 4326),
 'VEHICLE_CLASS_RESTRICTED', TRUE);
