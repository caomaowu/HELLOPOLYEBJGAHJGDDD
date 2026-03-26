ALTER TABLE position_activity_log
    ADD COLUMN fee DECIMAL(20, 8) NOT NULL DEFAULT 0.0 AFTER actual_amount;
