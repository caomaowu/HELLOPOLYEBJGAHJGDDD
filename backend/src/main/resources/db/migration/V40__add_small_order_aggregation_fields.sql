ALTER TABLE copy_trading
ADD COLUMN small_order_aggregation_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用小额订单聚合',
ADD COLUMN small_order_aggregation_window_seconds INT NOT NULL DEFAULT 300 COMMENT '小额订单聚合窗口（秒）';

ALTER TABLE copy_trading_templates
ADD COLUMN small_order_aggregation_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用小额订单聚合',
ADD COLUMN small_order_aggregation_window_seconds INT NOT NULL DEFAULT 300 COMMENT '小额订单聚合窗口（秒）';
