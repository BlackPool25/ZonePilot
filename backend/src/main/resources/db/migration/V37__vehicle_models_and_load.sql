-- V37__vehicle_models_and_load.sql
-- Vehicle spec catalog (public Indian CV specs: GVW/payload/fuel/mileage) +
-- per-stop load event log. TourStop already carries manifest weight_kg; this
-- closes the loop: capacity lives on the model, LoadService hard-blocks
-- overweight tours, and load_event keeps current + historical load per vehicle.
CREATE TABLE vehicle_model (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(60) NOT NULL UNIQUE,
    make             VARCHAR(60) NOT NULL,
    model            VARCHAR(80) NOT NULL,
    vehicle_class    VARCHAR(20) NOT NULL,
    gvw_kg           DOUBLE PRECISION NOT NULL,
    payload_kg       DOUBLE PRECISION NOT NULL,
    fuel_type        VARCHAR(20) NOT NULL,
    rated_mileage_kmpl DOUBLE PRECISION
);

-- Rated figures from OEM/portal specs (indicative; managers can correct).
INSERT INTO vehicle_model (code, make, model, vehicle_class, gvw_kg, payload_kg, fuel_type, rated_mileage_kmpl) VALUES
 ('TVS-XL-COURIER',   'TVS',          'XL100 Courier',      'TWO_WHEELER',  150,   100, 'PETROL',   60),
 ('BAJAJ-MAXIMA-C',   'Bajaj',        'Maxima Cargo CNG',   'LCV',         1017,   500, 'CNG',      30),
 ('TATA-ACE-GOLD-D',  'Tata',         'Ace Gold Diesel',    'LCV',         1615,   750, 'DIESEL',   22),
 ('TATA-ACE-EV',      'Tata',         'Ace EV',             'LCV',         2120,   600, 'ELECTRIC', NULL),
 ('TATA-INTRA-V30',   'Tata',         'Intra V30',          'LCV',         3150,  1300, 'DIESEL',   19),
 ('MAH-BOLERO-MAXIX', 'Mahindra',     'Bolero Maxx Pickup', 'LCV',         3000,  1500, 'DIESEL',   16),
 ('AL-BADA-DOST',     'Ashok Leyland','Bada Dost',          'LCV',         3490,  2000, 'DIESEL',   17),
 ('TATA-407-SFC',     'Tata',         '407 SFC',            'LCV',         4650,  2500, 'DIESEL',   12),
 ('EICHER-2049-14',   'Eicher',       'Pro 2049 14ft',      'HCV',         4990,  4000, 'DIESEL',   10),
 ('EICHER-3015-17',   'Eicher',       'Pro 3015 17ft',      'HCV',         8990,  5000, 'DIESEL',    8),
 ('EICHER-3018-19',   'Eicher',       'Pro 3018 19ft',      'HCV',        11990,  7000, 'DIESEL',    7),
 ('TATA-LPT1613-22',  'Tata',         'LPT 1613 22ft',      'HCV',        16200, 10000, 'DIESEL',    6);

ALTER TABLE vehicle ADD COLUMN model_id BIGINT REFERENCES vehicle_model(id);

CREATE TABLE load_event (
    id                BIGSERIAL PRIMARY KEY,
    vehicle_id        BIGINT NOT NULL REFERENCES vehicle(id),
    tour_id           BIGINT REFERENCES tour(id),
    stop_id           BIGINT REFERENCES tour_stop(id),
    delta_kg          DOUBLE PRECISION NOT NULL,
    running_total_kg  DOUBLE PRECISION NOT NULL,
    source            VARCHAR(20) NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_load_event_vehicle_time ON load_event (vehicle_id, recorded_at DESC);
