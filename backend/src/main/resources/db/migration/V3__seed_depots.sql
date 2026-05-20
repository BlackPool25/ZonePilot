-- V3__seed_depots.sql
-- 3 depots with real Bangalore coordinates

INSERT INTO depot (name, address, location) VALUES
('HSR Layout Depot', '27th Main Road, HSR Layout Sector 1, Bengaluru 560102',
 ST_SetSRID(ST_Point(77.6389, 12.9116), 4326)),
('Yeshwantpur Depot', 'Tumkur Road, Yeshwantpur, Bengaluru 560022',
 ST_SetSRID(ST_Point(77.5535, 13.0241), 4326)),
('Electronic City Depot', 'Electronics City Phase 1, Hosur Road, Bengaluru 560100',
 ST_SetSRID(ST_Point(77.6770, 12.8399), 4326));
