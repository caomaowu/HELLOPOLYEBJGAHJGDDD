ALTER TABLE wallet_accounts
    ADD COLUMN builder_api_key VARCHAR(500) NULL COMMENT 'Builder API Key（账户级，加密存储）' AFTER api_passphrase,
    ADD COLUMN builder_secret VARCHAR(500) NULL COMMENT 'Builder Secret（账户级，加密存储）' AFTER builder_api_key,
    ADD COLUMN builder_passphrase VARCHAR(500) NULL COMMENT 'Builder Passphrase（账户级，加密存储）' AFTER builder_secret;
