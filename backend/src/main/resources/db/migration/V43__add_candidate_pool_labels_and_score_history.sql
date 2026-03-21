ALTER TABLE trader_candidate_pool
    ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否收藏' AFTER last_market_slug,
    ADD COLUMN blacklisted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否拉黑' AFTER favorite,
    ADD COLUMN manual_note TEXT NULL COMMENT '人工备注' AFTER blacklisted,
    ADD COLUMN manual_tags_json TEXT NULL COMMENT '人工标签列表' AFTER manual_note;

ALTER TABLE trader_candidate_pool
    ADD KEY idx_trader_candidate_pool_favorite (favorite),
    ADD KEY idx_trader_candidate_pool_blacklisted (blacklisted);

CREATE TABLE IF NOT EXISTS trader_candidate_score_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NULL COMMENT '候选池ID',
    address VARCHAR(42) NOT NULL COMMENT 'Trader 地址',
    source VARCHAR(50) NOT NULL DEFAULT 'recommendation' COMMENT '评分来源',
    recommendation_score INT NULL COMMENT '推荐分',
    risk_score INT NULL COMMENT '风险分',
    low_risk BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否低风险',
    estimated_roi_rate DECIMAL(18, 6) NULL COMMENT '估算ROI比例',
    estimated_drawdown_rate DECIMAL(18, 6) NULL COMMENT '估算回撤比例',
    market_concentration_rate DECIMAL(18, 6) NULL COMMENT '市场集中度比例',
    active_days INT NULL COMMENT '活跃天数',
    current_position_count INT NULL COMMENT '持仓数',
    estimated_total_pnl DECIMAL(36, 8) NULL COMMENT '估算总盈亏',
    recent_trade_count INT NOT NULL DEFAULT 0 COMMENT '近期成交笔数',
    distinct_markets INT NOT NULL DEFAULT 0 COMMENT '去重市场数',
    last_seen_at BIGINT NULL COMMENT '最近活跃时间',
    tags_json TEXT NULL COMMENT '推荐标签快照',
    reasons_json TEXT NULL COMMENT '推荐原因快照',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    KEY idx_trader_candidate_score_history_address_created (address, created_at),
    KEY idx_trader_candidate_score_history_candidate_created (candidate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Trader 候选评分历史';
