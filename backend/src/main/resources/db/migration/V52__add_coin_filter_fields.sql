-- 为跟单配置与模板增加币种过滤字段
ALTER TABLE copy_trading
ADD COLUMN coin_filter_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '币种过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER market_series,
ADD COLUMN coin_symbols JSON NULL COMMENT '币种过滤列表（JSON数组，如 ["BTC","ETH"]）' AFTER coin_filter_mode;

ALTER TABLE copy_trading_templates
ADD COLUMN coin_filter_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '币种过滤模式：DISABLED/WHITELIST/BLACKLIST' AFTER market_series,
ADD COLUMN coin_symbols JSON NULL COMMENT '币种过滤列表（JSON数组，如 ["BTC","ETH"]）' AFTER coin_filter_mode;
