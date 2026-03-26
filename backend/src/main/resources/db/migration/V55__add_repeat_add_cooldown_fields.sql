ALTER TABLE copy_trading
    ADD COLUMN repeat_add_cooldown_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN repeat_add_cooldown_seconds INT NULL;

ALTER TABLE copy_trading_templates
    ADD COLUMN repeat_add_cooldown_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN repeat_add_cooldown_seconds INT NULL;
