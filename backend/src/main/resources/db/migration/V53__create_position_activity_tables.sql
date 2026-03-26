CREATE TABLE position_activity_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    outcome_index INT NULL,
    side VARCHAR(100) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    trade_side VARCHAR(10) NOT NULL,
    event_time BIGINT NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    actual_amount DECIMAL(20, 8) NOT NULL,
    remaining_quantity DECIMAL(20, 8) NOT NULL,
    source VARCHAR(32) NOT NULL,
    trade_id VARCHAR(100) NULL,
    order_id VARCHAR(100) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uk_position_activity_trade UNIQUE (account_id, trade_id),
    CONSTRAINT uk_position_activity_order_source UNIQUE (account_id, order_id, source),
    INDEX idx_position_activity_position_time (account_id, market_id, outcome_index, side, event_time DESC),
    INDEX idx_position_activity_event_time (event_time),
    INDEX idx_position_activity_source (source)
);

CREATE TABLE position_activity_sync_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    initialized_at BIGINT NOT NULL,
    last_synced_trade_time BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uk_position_activity_sync_account UNIQUE (account_id)
);

