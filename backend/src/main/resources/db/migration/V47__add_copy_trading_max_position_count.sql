ALTER TABLE copy_trading
ADD COLUMN max_position_count INT NULL COMMENT '最大活跃仓位数量，NULL表示不启用' AFTER max_position_value;
