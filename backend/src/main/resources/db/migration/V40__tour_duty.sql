-- V40__tour_duty.sql
-- Driver duty state per tour (ELD hybrid): OFF_DUTY/ON_DUTY/DRIVING/BREAK.

ALTER TABLE tour
    ADD COLUMN IF NOT EXISTS duty_status VARCHAR(20) NOT NULL DEFAULT 'OFF_DUTY';
