-- V9__indexes.sql
-- GIST spatial indexes and B-tree indexes

-- SPATIAL INDEXES (GIST)
CREATE INDEX idx_gist_vehicle_position_log_position ON vehicle_position_log USING GIST(position);
CREATE INDEX idx_gist_zone_restriction_boundary ON zone_restriction USING GIST(boundary);
CREATE INDEX idx_gist_dispatch_route_geometry ON dispatch_route USING GIST(planned_route_geometry);
CREATE INDEX idx_gist_depot_location ON depot USING GIST(location);
CREATE INDEX idx_gist_simulation_path_waypoints ON simulation_path USING GIST(waypoints);

-- B-TREE INDEXES
CREATE INDEX idx_btree_position_log_vehicle_id ON vehicle_position_log(vehicle_id);
CREATE INDEX idx_btree_position_log_recorded_at ON vehicle_position_log(recorded_at DESC);
CREATE INDEX idx_btree_breach_log_vehicle_id ON zone_breach_log(vehicle_id);
CREATE INDEX idx_btree_breach_log_breach_time ON zone_breach_log(breach_time DESC);
CREATE INDEX idx_btree_breach_log_zone_id ON zone_breach_log(zone_id);
CREATE INDEX idx_btree_vehicle_class ON vehicle(vehicle_class);
CREATE INDEX idx_btree_zone_rule_zone_id ON zone_restriction_rule(zone_id);
CREATE INDEX idx_btree_zone_rule_is_active ON zone_restriction_rule(is_active);
CREATE INDEX idx_btree_dispatch_route_vehicle ON dispatch_route(vehicle_id);
CREATE INDEX idx_btree_dispatch_route_status ON dispatch_route(status);
