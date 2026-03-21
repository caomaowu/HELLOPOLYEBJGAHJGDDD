ALTER TABLE copy_trading
ADD COLUMN adaptive_min_ratio DECIMAL(20, 8) NULL COMMENT '自适应最小比例',
ADD COLUMN adaptive_max_ratio DECIMAL(20, 8) NULL COMMENT '自适应最大比例',
ADD COLUMN adaptive_threshold DECIMAL(20, 8) NULL COMMENT '自适应阈值（USDC）',
ADD COLUMN multiplier_mode VARCHAR(10) NOT NULL DEFAULT 'NONE' COMMENT 'multiplier 模式：NONE/SINGLE/TIERED',
ADD COLUMN trade_multiplier DECIMAL(20, 8) NULL COMMENT '单一 multiplier',
ADD COLUMN tiered_multipliers JSON NULL COMMENT '分层 multiplier 配置',
ADD COLUMN max_daily_volume DECIMAL(20, 8) NULL COMMENT '每日最大成交额（USDC）';

ALTER TABLE copy_trading_templates
ADD COLUMN adaptive_min_ratio DECIMAL(20, 8) NULL COMMENT '自适应最小比例',
ADD COLUMN adaptive_max_ratio DECIMAL(20, 8) NULL COMMENT '自适应最大比例',
ADD COLUMN adaptive_threshold DECIMAL(20, 8) NULL COMMENT '自适应阈值（USDC）',
ADD COLUMN multiplier_mode VARCHAR(10) NOT NULL DEFAULT 'NONE' COMMENT 'multiplier 模式：NONE/SINGLE/TIERED',
ADD COLUMN trade_multiplier DECIMAL(20, 8) NULL COMMENT '单一 multiplier',
ADD COLUMN tiered_multipliers JSON NULL COMMENT '分层 multiplier 配置',
ADD COLUMN max_daily_volume DECIMAL(20, 8) NULL COMMENT '每日最大成交额（USDC）';

ALTER TABLE backtest_task
ADD COLUMN adaptive_min_ratio DECIMAL(20, 8) NULL COMMENT '自适应最小比例',
ADD COLUMN adaptive_max_ratio DECIMAL(20, 8) NULL COMMENT '自适应最大比例',
ADD COLUMN adaptive_threshold DECIMAL(20, 8) NULL COMMENT '自适应阈值（USDC）',
ADD COLUMN multiplier_mode VARCHAR(10) NOT NULL DEFAULT 'NONE' COMMENT 'multiplier 模式：NONE/SINGLE/TIERED',
ADD COLUMN trade_multiplier DECIMAL(20, 8) NULL COMMENT '单一 multiplier',
ADD COLUMN tiered_multipliers JSON NULL COMMENT '分层 multiplier 配置',
ADD COLUMN max_daily_volume DECIMAL(20, 8) NULL COMMENT '每日最大成交额（USDC）';
