-- ============================================
-- V46: 为跟单配置/模板/市场缓存添加市场过滤字段
-- 1. copy_trading 增加分类/周期/系列过滤字段
-- 2. copy_trading_templates 增加分类/周期/系列过滤字段
-- 3. markets 增加系列前缀/周期/市场来源类型字段
-- ============================================

ALTER TABLE copy_trading
ADD COLUMN market_category_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '市场分类过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER keywords,
ADD COLUMN market_categories JSON NULL COMMENT '市场分类过滤列表（JSON数组）' AFTER market_category_mode,
ADD COLUMN market_interval_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '市场周期过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER market_categories,
ADD COLUMN market_intervals JSON NULL COMMENT '市场周期过滤列表（JSON数组，单位秒）' AFTER market_interval_mode,
ADD COLUMN market_series_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '市场系列过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER market_intervals,
ADD COLUMN market_series JSON NULL COMMENT '市场系列过滤列表（JSON数组，如 btc-updown-15m）' AFTER market_series_mode;

ALTER TABLE copy_trading_templates
ADD COLUMN market_category_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '市场分类过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER max_price,
ADD COLUMN market_categories JSON NULL COMMENT '市场分类过滤列表（JSON数组）' AFTER market_category_mode,
ADD COLUMN market_interval_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '市场周期过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER market_categories,
ADD COLUMN market_intervals JSON NULL COMMENT '市场周期过滤列表（JSON数组，单位秒）' AFTER market_interval_mode,
ADD COLUMN market_series_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '市场系列过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER market_intervals,
ADD COLUMN market_series JSON NULL COMMENT '市场系列过滤列表（JSON数组，如 btc-updown-15m）' AFTER market_series_mode;

ALTER TABLE markets
ADD COLUMN series_slug_prefix VARCHAR(128) NULL COMMENT '市场系列前缀，如 btc-updown-15m' AFTER event_slug,
ADD COLUMN interval_seconds INT NULL COMMENT '市场周期秒数，如 300/900/3600/14400/86400' AFTER series_slug_prefix,
ADD COLUMN market_source_type VARCHAR(32) NOT NULL DEFAULT 'GENERIC' COMMENT '市场来源类型：CRYPTO_UPDOWN/TIMED_SERIES/GENERIC' AFTER interval_seconds;
