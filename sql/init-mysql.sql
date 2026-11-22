-- =====================================================================================
-- BalanceWise 个人账务管理与统计分析系统 · 全量数据库初始化脚本（MySQL）
-- =====================================================================================
-- 适用版本 : MySQL 8.x（5.7+ 兼容）
-- 执行方式 : mysql -u root -p --default-character-set=utf8mb4 < sql/init-mysql.sql
--            （或在此文件于 Navicat / DataGrip / IDEA Database 等工具中直接运行）
-- 脚本内容 : ① 建库（utf8mb4）→ ② 七张业务表（含注释/索引/时间戳自动更新）→ ③ 演示数据（可选）
--
-- ⚠ 重复执行会先 DROP 七张业务表再重建，库内已有数据将被清空，请确认后再执行。
-- 说明 : backend/src/main/resources/schema.sql 为 H2 内存库（单元测试）专用的最小结构；
--        本脚本是 MySQL 环境的正式全量初始化脚本，表结构与 schema.sql 字段一一对应，
--        差异仅在于：本脚本带 ON UPDATE CURRENT_TIMESTAMP（应用无自动填充，
--        MySQL 端由该子句保证 updated_at 在行更新时刷新）与完整的库/表/字段注释。
--
-- 演示数据 : 已拆分至 sql/demo-data.sql（可选执行，含演示账号 demo/123456 与自洽流水）。
--            全新初始化默认不带演示数据，直接注册自己的账号开始使用。
-- =====================================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------------
-- ① 建库
-- ------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS balancewisedb
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE balancewisedb;

DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS transaction_record;
DROP TABLE IF EXISTS import_batch;
DROP TABLE IF EXISTS import_alias;
DROP TABLE IF EXISTS ai_config;

-- ------------------------------------------------------------------
-- ② 建表（五张业务表）
-- ------------------------------------------------------------------

-- 用户表：注册信息与登录凭证
CREATE TABLE `user` (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(32)  NOT NULL                COMMENT '登录用户名（唯一）',
  password_hash VARCHAR(100) NOT NULL                COMMENT '密码哈希（BCrypt）',
  nickname      VARCHAR(32)  NULL                    COMMENT '昵称（注册未填时取用户名）',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 账户表：资产/负债账户，负债余额为负（欠款）
CREATE TABLE account (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT        NOT NULL                COMMENT '所属用户 id',
  name            VARCHAR(32)   NOT NULL                COMMENT '账户名称',
  type            VARCHAR(16)   NOT NULL                COMMENT '账户类别：asset 资产 / liability 负债',
  sub_type        VARCHAR(32)   NOT NULL                COMMENT '子类型：cash现金/debit_card借记卡/wechat微信/alipay支付宝/yuebao零钱通/credit_card信用卡/huabei花呗/custom自定义',
  balance         DECIMAL(12,2) NOT NULL DEFAULT 0      COMMENT '当前余额（负债为负数）',
  initial_balance DECIMAL(12,2) NOT NULL DEFAULT 0      COMMENT '初始余额（负债录入正数欠款，存储取负）',
  remark          VARCHAR(255)  NULL                    COMMENT '备注',
  is_archived     TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否归档：0 否 / 1 已归档（不可记账、不参与汇总）',
  sort_order      INT           NOT NULL DEFAULT 0      COMMENT '排序号（小者在前）',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_account_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户表';

-- 分类表：两级收支分类（预置分类随注册播种，不可删除）
CREATE TABLE category (
  id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id    BIGINT      NOT NULL                COMMENT '所属用户 id',
  parent_id  BIGINT      NULL                    COMMENT '父分类 id（NULL 为一级分类）',
  name       VARCHAR(32) NOT NULL                COMMENT '分类名称（同级同类型下唯一，应用层校验）',
  kind       VARCHAR(8)  NOT NULL                COMMENT '分类类型：expense 支出 / income 收入',
  is_system  TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '是否预置分类：0 自定义 / 1 预置（不可删除）',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_category_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收支分类表';

-- 流水表：支出/收入/转账记录（软删除；跨用户无物理外键，完整性由应用层保证）
CREATE TABLE transaction_record (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT        NOT NULL                COMMENT '所属用户 id',
  type            VARCHAR(8)    NOT NULL                COMMENT '流水类型：expense 支出 / income 收入 / transfer 转账',
  from_account_id BIGINT        NULL                    COMMENT '转出/支出账户 id（收入为 NULL）',
  to_account_id   BIGINT        NULL                    COMMENT '转入/收入账户 id（支出为 NULL）',
  amount          DECIMAL(12,2) NOT NULL                COMMENT '金额（恒为正，方向由类型与账户位决定）',
  category_id     BIGINT        NULL                    COMMENT '分类 id（转账无分类）',
  occurred_at     DATETIME      NOT NULL                COMMENT '交易发生时间',
  counterparty    VARCHAR(128)  NULL                    COMMENT '交易对象（商户/对方）',
  item            VARCHAR(255)  NULL                    COMMENT '商品/备注',
  source          VARCHAR(10)   NOT NULL                COMMENT '来源：manual 手工 / alipay / wechat 导入 / reconcile 余额校正',
  external_id     VARCHAR(64)   NULL                    COMMENT '平台交易单号（导入防重键）',
  import_batch_id BIGINT        NULL                    COMMENT '所属导入批次 id',
  is_deleted      TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否软删除：0 否 / 1 已删除（删除即冲销余额）',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_txn_user_time    (user_id, occurred_at),
  KEY idx_txn_user_src_ext (user_id, source, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易流水表';

-- 导入批次表：账单导入的批次元信息（撤销入账会还原批次内流水的余额影响）
CREATE TABLE import_batch (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id        BIGINT        NOT NULL                COMMENT '所属用户 id',
  platform       VARCHAR(8)    NOT NULL                COMMENT '账单平台：alipay / wechat',
  file_name      VARCHAR(255)  NOT NULL                COMMENT '上传文件名',
  record_count   INT           NOT NULL                COMMENT '入账流水条数',
  total_amount   DECIMAL(14,2) NOT NULL                COMMENT '入账金额合计',
  status         VARCHAR(12)   NOT NULL                COMMENT '批次状态：confirmed 已入账 / rolled_back 已撤销',
  imported_at    DATETIME      NULL                    COMMENT '入账时间',
  rolled_back_at DATETIME      NULL                    COMMENT '撤销时间',
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_batch_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单导入批次表';

-- 导入映射记忆表：用户在导入预览中修正分类/账户并勾选"记住"后沉淀的个人映射
-- （UC-4.10），下次导入优先于内置规则命中。kind 进唯一键：同一商户可同时存在
-- 支出/收入两条记忆（退款行不会被支出记忆污染）；账户映射统一 kind='any'。
CREATE TABLE import_alias (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT       NOT NULL                COMMENT '所属用户 id',
  alias_type  VARCHAR(10)  NOT NULL                COMMENT '记忆类型：category 分类映射 / account 付款方式→账户',
  kind        VARCHAR(10)  NOT NULL DEFAULT 'any'  COMMENT '分类映射存 expense/income；账户映射统一 any',
  alias_text  VARCHAR(128) NOT NULL                COMMENT '记忆文本：分类=对方（为空取商品名）；账户=付款方式原文',
  target_id   BIGINT       NOT NULL                COMMENT '目标 id：category.id 或 account.id',
  target_name VARCHAR(64)  NOT NULL                COMMENT '目标名称快照（记忆管理列表展示用）',
  hit_count   INT          NOT NULL DEFAULT 0      COMMENT '累计命中次数',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alias_user_type_text (user_id, alias_type, alias_text, kind),
  KEY idx_alias_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入映射记忆表';

-- AI 分类配置表：用户在个人信息页配置的大模型接入参数（用户级，一人一行）。
-- api_key 为用户自备 Key（OpenAI 兼容接口，默认硅基流动）；接口层只存不回显。
-- 未配置/未启用的用户自动降级：读服务端全局配置（balancewise.ai），再无则跳过 AI 层。
CREATE TABLE ai_config (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id    BIGINT       NOT NULL                COMMENT '所属用户 id',
  enabled    TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否启用 AI 分类：0 否 / 1 是',
  base_url   VARCHAR(255) NOT NULL                COMMENT 'OpenAI 兼容接口地址（如 https://api.siliconflow.cn/v1）',
  api_key    VARCHAR(255) NOT NULL                COMMENT 'API Key（用户自备，接口只存不回显）',
  model      VARCHAR(128) NOT NULL                COMMENT '模型名（如 Qwen/Qwen2.5-7B-Instruct）',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_config_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 分类配置表（用户级）';

SET FOREIGN_KEY_CHECKS = 1;
