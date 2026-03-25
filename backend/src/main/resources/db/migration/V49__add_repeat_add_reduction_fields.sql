ALTER TABLE copy_trading
    ADD COLUMN repeat_add_reduction_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN repeat_add_reduction_strategy VARCHAR(20) NOT NULL DEFAULT 'UNIFORM',
    ADD COLUMN repeat_add_reduction_value_type VARCHAR(20) NOT NULL DEFAULT 'PERCENT',
    ADD COLUMN repeat_add_reduction_percent DECIMAL(10, 4) NULL,
    ADD COLUMN repeat_add_reduction_fixed_amount DECIMAL(20, 8) NULL;

ALTER TABLE copy_trading_templates
    ADD COLUMN repeat_add_reduction_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN repeat_add_reduction_strategy VARCHAR(20) NOT NULL DEFAULT 'UNIFORM',
    ADD COLUMN repeat_add_reduction_value_type VARCHAR(20) NOT NULL DEFAULT 'PERCENT',
    ADD COLUMN repeat_add_reduction_percent DECIMAL(10, 4) NULL,
    ADD COLUMN repeat_add_reduction_fixed_amount DECIMAL(20, 8) NULL;

ALTER TABLE backtest_task
    ADD COLUMN repeat_add_reduction_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN repeat_add_reduction_strategy VARCHAR(20) NOT NULL DEFAULT 'UNIFORM',
    ADD COLUMN repeat_add_reduction_value_type VARCHAR(20) NOT NULL DEFAULT 'PERCENT',
    ADD COLUMN repeat_add_reduction_percent DECIMAL(10, 4) NULL,
    ADD COLUMN repeat_add_reduction_fixed_amount DECIMAL(20, 8) NULL;

CREATE TABLE copy_trading_repeat_add_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    copy_trading_id BIGINT NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    outcome_index INT NOT NULL,
    first_buy_amount DECIMAL(20, 8) NOT NULL,
    buy_count INT NOT NULL,
    last_buy_amount DECIMAL(20, 8) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repeat_add_state (copy_trading_id, market_id, outcome_index),
    KEY idx_repeat_add_state_copy_trading (copy_trading_id),
    KEY idx_repeat_add_state_market (market_id, outcome_index)
);
