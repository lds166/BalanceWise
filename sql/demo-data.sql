-- =====================================================================================
-- BalanceWise · 可选演示数据（独立于 init-mysql.sql，按需执行）
-- =====================================================================================
-- 内容 : 演示账号 demo（密码 123456）+ 39 个预置分类 + 4 个账户 + 近两个月自洽流水
--        与 1 个已入账导入批次（可用于「余额校正」演示：差额为 0 会被拒绝）。
-- 执行 : mysql -u root -p --default-character-set=utf8mb4 < sql/demo-data.sql
-- ⚠ 仅用于全新初始化后的空库演示；真实使用请勿对同一库执行本文件。
-- =====================================================================================
USE balancewisedb;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================================

-- ------------------------------------------------------------------
-- ③-1 演示账号：demo / 123456
-- ------------------------------------------------------------------
INSERT INTO `user` (id, username, password_hash, nickname)
VALUES (1, 'demo', '$2b$10$T.rcjbzCkVTowLKFEQVJL.d4Qr5Sucr7i3OcKzE1mWWoMpNE8ZaFG', '演示用户');

-- ------------------------------------------------------------------
-- ③-2 预置分类（与 CategoryService 注册播种完全一致，共 39 个）
--      支出 1~34（一级/二级），收入 35~39（均为一级）
-- ------------------------------------------------------------------
INSERT INTO category (id, user_id, parent_id, name, kind, is_system) VALUES
  (1,  1, NULL, '餐饮',     'expense', 1),
  (2,  1, 1,    '三餐',     'expense', 1),
  (3,  1, 1,    '外卖',     'expense', 1),
  (4,  1, 1,    '聚餐',     'expense', 1),
  (5,  1, 1,    '零食饮料', 'expense', 1),
  (6,  1, NULL, '交通',     'expense', 1),
  (7,  1, 6,    '公交地铁', 'expense', 1),
  (8,  1, 6,    '打车',     'expense', 1),
  (9,  1, 6,    '火车机票', 'expense', 1),
  (10, 1, 6,    '加油停车', 'expense', 1),
  (11, 1, NULL, '购物',     'expense', 1),
  (12, 1, 11,   '服饰鞋包', 'expense', 1),
  (13, 1, 11,   '数码家电', 'expense', 1),
  (14, 1, 11,   '日用百货', 'expense', 1),
  (15, 1, 11,   '美妆护肤', 'expense', 1),
  (16, 1, NULL, '居住',     'expense', 1),
  (17, 1, 16,   '房租',     'expense', 1),
  (18, 1, 16,   '水电燃气', 'expense', 1),
  (19, 1, 16,   '物业宽带', 'expense', 1),
  (20, 1, NULL, '娱乐',     'expense', 1),
  (21, 1, 20,   '电影游戏', 'expense', 1),
  (22, 1, 20,   '旅游度假', 'expense', 1),
  (23, 1, 20,   '运动健身', 'expense', 1),
  (24, 1, 20,   '会员充值', 'expense', 1),
  (25, 1, NULL, '医疗',     'expense', 1),
  (26, 1, 25,   '药品',     'expense', 1),
  (27, 1, 25,   '门诊住院', 'expense', 1),
  (28, 1, NULL, '教育',     'expense', 1),
  (29, 1, 28,   '书籍资料', 'expense', 1),
  (30, 1, 28,   '课程培训', 'expense', 1),
  (31, 1, NULL, '人情往来', 'expense', 1),
  (32, 1, 31,   '红包礼金', 'expense', 1),
  (33, 1, 31,   '请客送礼', 'expense', 1),
  (34, 1, NULL, '其他支出', 'expense', 1),
  (35, 1, NULL, '工资薪资', 'income', 1),
  (36, 1, NULL, '兼职副业', 'income', 1),
  (37, 1, NULL, '理财收益', 'income', 1),
  (38, 1, NULL, '红包礼金', 'income', 1),
  (39, 1, NULL, '其他收入', 'income', 1);

-- ------------------------------------------------------------------
-- ③-3 账户（现金 / 微信零钱 / 支付宝 / 招行信用卡）
--      balance = initial_balance + 净流水（负债为负，见文末自检查询）
-- ------------------------------------------------------------------
INSERT INTO account (id, user_id, name, type, sub_type, balance, initial_balance, remark, is_archived, sort_order) VALUES
  (1, 1, '现金',     'asset',     'cash',        117.10,   0,      '日常零花',           0, 0),
  (2, 1, '微信零钱', 'asset',     'wechat',      1468.10,  0,      '微信收付款',         0, 1),
  (3, 1, '支付宝',   'asset',     'alipay',      11272.00, 500.00, '工资与生活主账户',   0, 2),
  (4, 1, '招行信用卡', 'liability', 'credit_card', -217.00, 0,      '本月账单待还 217 元', 0, 3);

-- ------------------------------------------------------------------
-- ③-4 导入批次（支付宝账单，已入账）
-- ------------------------------------------------------------------
INSERT INTO import_batch (id, user_id, platform, file_name, record_count, total_amount, status, imported_at)
VALUES (1, 1, 'alipay', 'alipay_bill_demo.csv', 3, 86.70, 'confirmed',
        NOW() - INTERVAL '5' DAY - INTERVAL '21' HOUR);

-- ------------------------------------------------------------------
-- ③-5 流水（近两个月，33 条；时间随执行日滚动，保证首页/趋势/构成图表有数据）
--      支出：from = 付款账户；收入：to = 收款账户；转账：from→to
-- ------------------------------------------------------------------
INSERT INTO transaction_record
  (user_id, type, from_account_id, to_account_id, amount, category_id, occurred_at, counterparty, item, source, external_id, import_batch_id) VALUES
  -- ---- 上月 ----
  (1, 'income',   NULL, 3,    8500.00, 35, NOW() - INTERVAL '55' DAY - INTERVAL '10' HOUR, '公司',       '工资',         'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   25.50,  2, NOW() - INTERVAL '53' DAY - INTERVAL '12' HOUR, '楼下早餐店', '早餐',         'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   18.00,  3, NOW() - INTERVAL '53' DAY - INTERVAL '19' HOUR, '美团外卖',   '晚餐',         'manual',   NULL, NULL),
  (1, 'expense',  1,    NULL,    4.00,  7, NOW() - INTERVAL '52' DAY - INTERVAL '8' HOUR,  '地铁',       '通勤',         'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   23.60,  8, NOW() - INTERVAL '51' DAY - INTERVAL '21' HOUR, '滴滴出行',   '打车回家',     'manual',   NULL, NULL),
  (1, 'expense',  3,    NULL, 1800.00, 17, NOW() - INTERVAL '50' DAY - INTERVAL '9' HOUR,  '房东',       '房租',         'manual',   NULL, NULL),
  (1, 'expense',  3,    NULL,  120.80, 18, NOW() - INTERVAL '47' DAY - INTERVAL '20' HOUR, '燃气公司',   '水电燃气',     'manual',   NULL, NULL),
  (1, 'transfer', 3,    2,    1000.00, NULL, NOW() - INTERVAL '45' DAY - INTERVAL '11' HOUR, NULL,        '转入微信零钱', 'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   56.30, 14, NOW() - INTERVAL '44' DAY - INTERVAL '15' HOUR, '永辉超市',   '日用杂货',     'manual',   NULL, NULL),
  (1, 'income',   NULL, 2,     600.00, 36, NOW() - INTERVAL '42' DAY - INTERVAL '14' HOUR, '平台结算',   '周末兼职',     'manual',   NULL, NULL),
  (1, 'expense',  1,    NULL,   32.40,  5, NOW() - INTERVAL '40' DAY - INTERVAL '16' HOUR, '便利店',     '零食饮料',     'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   45.00, 21, NOW() - INTERVAL '38' DAY - INTERVAL '20' HOUR, '电影院',     '周末观影',     'manual',   NULL, NULL),
  (1, 'expense',  4,    NULL,  299.00, 13, NOW() - INTERVAL '35' DAY - INTERVAL '14' HOUR, '京东商城',   '机械键盘',     'manual',   NULL, NULL),
  (1, 'income',   NULL, 3,      35.50, 37, NOW() - INTERVAL '33' DAY - INTERVAL '9' HOUR,  '余额宝',     '理财收益',     'manual',   NULL, NULL),
  (1, 'expense',  4,    NULL,  158.00, 12, NOW() - INTERVAL '31' DAY - INTERVAL '17' HOUR, '优衣库',     '秋冬外套',     'manual',   NULL, NULL),
  (1, 'transfer', 3,    1,     200.00, NULL, NOW() - INTERVAL '30' DAY - INTERVAL '10' HOUR, NULL,        '现金提现',     'manual',   NULL, NULL),
  (1, 'expense',  1,    NULL,   42.50, 26, NOW() - INTERVAL '29' DAY - INTERVAL '11' HOUR, '药店',       '感冒药',       'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   15.00, 24, NOW() - INTERVAL '28' DAY - INTERVAL '12' HOUR, '视频平台',   '会员月卡',     'manual',   NULL, NULL),
  -- ---- 本月 ----
  (1, 'income',   NULL, 3,    8500.00, 35, NOW() - INTERVAL '25' DAY - INTERVAL '10' HOUR, '公司',       '工资',         'manual',   NULL, NULL),
  (1, 'expense',  3,    NULL, 1800.00, 17, NOW() - INTERVAL '24' DAY - INTERVAL '9' HOUR,  '房东',       '房租',         'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   28.00,  2, NOW() - INTERVAL '22' DAY - INTERVAL '12' HOUR, '公司食堂',   '午餐',         'manual',   NULL, NULL),
  (1, 'expense',  1,    NULL,    4.00,  7, NOW() - INTERVAL '21' DAY - INTERVAL '8' HOUR,  '地铁',       '通勤',         'manual',   NULL, NULL),
  (1, 'expense',  3,    NULL,  500.00, 32, NOW() - INTERVAL '20' DAY - INTERVAL '18' HOUR, '朋友婚礼',   '红包',         'manual',   NULL, NULL),
  (1, 'income',   NULL, 2,     200.00, 38, NOW() - INTERVAL '18' DAY - INTERVAL '9' HOUR,  '父母',       '节日红包',     'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   21.50,  3, NOW() - INTERVAL '16' DAY - INTERVAL '12' HOUR, '美团外卖',   '午餐',         'manual',   NULL, NULL),
  (1, 'expense',  3,    NULL,   68.00, 29, NOW() - INTERVAL '14' DAY - INTERVAL '20' HOUR, '当当网',     '技术书籍',     'manual',   NULL, NULL),
  (1, 'transfer', 3,    4,     500.00, NULL, NOW() - INTERVAL '12' DAY - INTERVAL '19' HOUR, NULL,        '信用卡还款',   'manual',   NULL, NULL),
  (1, 'expense',  2,    NULL,   99.00, 23, NOW() - INTERVAL '10' DAY - INTERVAL '18' HOUR, '健身房',     '健身月卡',     'manual',   NULL, NULL),
  (1, 'expense',  3,    NULL,  188.00, 33, NOW() - INTERVAL '8' DAY - INTERVAL '19' HOUR,  '餐厅',       '朋友聚餐',     'manual',   NULL, NULL),
  (1, 'expense',  4,    NULL,  260.00, 10, NOW() - INTERVAL '6' DAY - INTERVAL '16' HOUR,  '中石化',     '加油',         'manual',   NULL, NULL),
  -- ---- 支付宝导入批次（batch 1，共 86.70 元）----
  (1, 'expense',  3,    NULL,   38.70,  2, NOW() - INTERVAL '6' DAY - INTERVAL '12' HOUR, '永辉超市',   '早餐杂货',     'alipay',   'DEMO-ALI-20261116-001', 1),
  (1, 'expense',  3,    NULL,   25.00,  3, NOW() - INTERVAL '6' DAY - INTERVAL '12' HOUR, '美团外卖',   '午餐',         'alipay',   'DEMO-ALI-20261116-002', 1),
  (1, 'expense',  3,    NULL,   23.00,  8, NOW() - INTERVAL '5' DAY - INTERVAL '18' HOUR, '滴滴出行',   '打车',         'alipay',   'DEMO-ALI-20261116-003', 1);

-- ------------------------------------------------------------------
-- ③-6 自检查询（执行后应看到 4 行 diff 均为 0，即余额与流水自洽）
-- ------------------------------------------------------------------
SELECT a.id, a.name, a.balance AS stored_balance,
       a.initial_balance + COALESCE(SUM(
         CASE WHEN t.to_account_id = a.id THEN t.amount
              WHEN t.from_account_id = a.id THEN -t.amount
              ELSE 0 END), 0) AS flow_computed,
       ROUND(a.balance - (a.initial_balance + COALESCE(SUM(
         CASE WHEN t.to_account_id = a.id THEN t.amount
              WHEN t.from_account_id = a.id THEN -t.amount
              ELSE 0 END), 0)), 2) AS diff
FROM account a
LEFT JOIN transaction_record t
       ON t.is_deleted = 0
      AND (t.from_account_id = a.id OR t.to_account_id = a.id)
GROUP BY a.id, a.name, a.balance, a.initial_balance
ORDER BY a.id;

SET FOREIGN_KEY_CHECKS = 1;
