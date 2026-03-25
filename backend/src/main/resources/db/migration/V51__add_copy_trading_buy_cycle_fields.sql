ALTER TABLE copy_trading
    ADD COLUMN buy_cycle_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN buy_cycle_run_seconds INT NULL,
    ADD COLUMN buy_cycle_pause_seconds INT NULL,
    ADD COLUMN buy_cycle_anchor_started_at BIGINT NULL;
