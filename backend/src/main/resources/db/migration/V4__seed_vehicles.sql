-- V4__seed_vehicles.sql
-- 9 vehicles: 3 x TWO_WHEELER, 3 x LCV, 3 x HCV

-- TWO_WHEELER (vehicles 1-3)
INSERT INTO vehicle (registration_number, vehicle_class, owner_name, depot_id, is_active) VALUES
('KA01-TW-0001', 'TWO_WHEELER', 'Rajesh Kumar', 1, TRUE),
('KA01-TW-0002', 'TWO_WHEELER', 'Priya Sharma', 2, TRUE),
('KA01-TW-0003', 'TWO_WHEELER', 'Amit Patel', 3, TRUE);

-- LCV (vehicles 4-6)
INSERT INTO vehicle (registration_number, vehicle_class, owner_name, depot_id, is_active) VALUES
('KA01-LCV-0004', 'LCV', 'Suresh Reddy', 1, TRUE),
('KA01-LCV-0005', 'LCV', 'Deepa Nair', 2, TRUE),
('KA01-LCV-0006', 'LCV', 'Vikram Singh', 3, TRUE);

-- HCV (vehicles 7-9)
INSERT INTO vehicle (registration_number, vehicle_class, owner_name, depot_id, is_active) VALUES
('KA01-HCV-0007', 'HCV', 'Transport Co Pvt Ltd', 1, TRUE),
('KA01-HCV-0008', 'HCV', 'Freight Lines Inc', 2, TRUE),
('KA01-HCV-0009', 'HCV', 'Bangalore Cargo Services', 3, TRUE);
