-- BalanceWise 建表脚本（user / account / category / transaction_record / import_batch / import_alias 共 6 张表）
-- MySQL 环境请优先使用 sql/init-mysql.sql（建库 balancewisedb + 建表 + 可选演示数据）。
-- 注意：本文件同时被测试环境的 H2 内存库（MODE=MySQL）加载初始化，
--       因此不能包含 CREATE DATABASE / USE 等 H2 不支持的语句。

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(32) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(32) NOT NULL,
  type VARCHAR(16) NOT NULL,
  sub_type VARCHAR(32) NOT NULL,
  balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  initial_balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  remark VARCHAR(255) NULL,
  is_archived TINYINT(1) NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_account_user ON account(user_id);

CREATE TABLE IF NOT EXISTS category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  name VARCHAR(32) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  is_system TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_category_user ON category(user_id);

CREATE TABLE IF NOT EXISTS transaction_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(8) NOT NULL,
  from_account_id BIGINT NULL,
  to_account_id BIGINT NULL,
  amount DECIMAL(12,2) NOT NULL,
  category_id BIGINT NULL,
  occurred_at DATETIME NOT NULL,
  counterparty VARCHAR(128) NULL,
  item VARCHAR(255) NULL,
  source VARCHAR(10) NOT NULL,
  external_id VARCHAR(64) NULL,
  import_batch_id BIGINT NULL,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_txn_user_time ON transaction_record(user_id, occurred_at);
CREATE INDEX idx_txn_user_src_ext ON transaction_record(user_id, source, external_id);

CREATE TABLE IF NOT EXISTS import_batch (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  platform VARCHAR(8) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  record_count INT NOT NULL,
  total_amount DECIMAL(14,2) NOT NULL,
  status VARCHAR(12) NOT NULL,
  imported_at DATETIME NULL,
  rolled_back_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_batch_user ON import_batch(user_id);

CREATE TABLE IF NOT EXISTS import_alias (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  alias_type VARCHAR(10) NOT NULL,
  kind VARCHAR(10) NOT NULL DEFAULT 'any',
  alias_text VARCHAR(128) NOT NULL,
  target_id BIGINT NOT NULL,
  target_name VARCHAR(64) NOT NULL,
  hit_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_alias_user_type_text ON import_alias(user_id, alias_type, alias_text, kind);
CREATE INDEX idx_alias_user ON import_alias(user_id);
