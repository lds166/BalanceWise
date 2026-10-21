/* ========== BalanceWise 原型模拟数据 ========== */

const USER = { username: "lids", nickname: "小东" };

/* 账户：负债账户 balance 为负（欠款） */
const ACCOUNTS = [
  { id: 1, name: "现金",            type: "asset",     subType: "cash",        balance: 1250.00,  archived: false },
  { id: 2, name: "招商储蓄卡",     type: "asset",     subType: "debit_card",  balance: 15320.50, archived: false },
  { id: 3, name: "微信零钱",       type: "asset",     subType: "wechat",      balance: 386.20,   archived: false },
  { id: 4, name: "支付宝余额",     type: "asset",     subType: "alipay",      balance: 502.75,   archived: false },
  { id: 5, name: "余额宝",         type: "asset",     subType: "yuebao",      balance: 26000.00, archived: false },
  { id: 6, name: "交通银行信用卡", type: "liability", subType: "credit_card", balance: -3150.40, archived: false },
  { id: 7, name: "花呗",           type: "liability", subType: "huabei",      balance: -892.10,  archived: false },
];

const SUB_TYPE_NAMES = {
  cash: "现金", debit_card: "储蓄卡", wechat: "微信零钱", alipay: "支付宝余额",
  yuebao: "余额宝", credit_card: "信用卡", huabei: "花呗", custom: "自定义",
};

/* 分类（两级） */
const CATEGORIES = [
  { id: 11, kind: "expense", name: "餐饮", children: ["三餐", "外卖", "聚餐", "零食饮料"] },
  { id: 12, kind: "expense", name: "交通", children: ["公交地铁", "打车", "火车机票", "加油停车"] },
  { id: 13, kind: "expense", name: "购物", children: ["服饰鞋包", "数码家电", "日用百货", "美妆护肤"] },
  { id: 14, kind: "expense", name: "居住", children: ["房租", "水电燃气", "物业宽带"] },
  { id: 15, kind: "expense", name: "娱乐", children: ["电影游戏", "旅游度假", "运动健身", "会员充值"] },
  { id: 16, kind: "expense", name: "医疗", children: ["药品", "门诊住院"] },
  { id: 17, kind: "expense", name: "教育", children: ["书籍资料", "课程培训"] },
  { id: 18, kind: "expense", name: "人情往来", children: ["红包礼金", "请客送礼"] },
  { id: 19, kind: "expense", name: "其他支出", children: [] },
  { id: 21, kind: "income", name: "工资薪资", children: [] },
  { id: 22, kind: "income", name: "兼职副业", children: [] },
  { id: 23, kind: "income", name: "理财收益", children: [] },
  { id: 24, kind: "income", name: "红包礼金", children: [] },
  { id: 25, kind: "income", name: "其他收入", children: [] },
];

/* 流水（近两个月样例） */
const TRANSACTIONS = [
  { id: 101, type: "income",  amount: 12500.00, account: "招商储蓄卡", category: "工资薪资", date: "2026-10-10 09:30", item: "10月工资", source: "manual" },
  { id: 102, type: "expense", amount: 36.50,   account: "余额宝",     category: "餐饮·外卖", date: "2026-10-10 12:20", item: "美团外卖午餐", source: "alipay", counterparty: "美团平台商户" },
  { id: 103, type: "expense", amount: 15.00,   account: "微信零钱",   category: "交通·公交地铁", date: "2026-10-10 08:45", item: "地铁通勤", source: "wechat" },
  { id: 104, type: "expense", amount: 89.00,   account: "花呗",       category: "购物·日用百货", date: "2026-10-09 21:12", item: "天猫超市纸巾洗护", source: "alipay", counterparty: "天猫超市" },
  { id: 105, type: "transfer", amount: 2000.00, from: "招商储蓄卡", to: "余额宝", category: "", date: "2026-10-09 10:00", item: "转入余额宝", source: "manual" },
  { id: 106, type: "expense", amount: 128.00,  account: "交通银行信用卡", category: "娱乐·会员充值", date: "2026-10-08 19:40", item: "视频年卡续费", source: "alipay" },
  { id: 107, type: "expense", amount: 245.60,  account: "微信零钱",   category: "餐饮·聚餐", date: "2026-10-07 18:30", item: "周末聚餐 AA", source: "wechat", counterparty: "海底捞火锅" },
  { id: 108, type: "income",  amount: 320.00,  account: "支付宝余额", category: "兼职副业", date: "2026-10-06 15:00", item: "设计私单尾款", source: "manual" },
  { id: 109, type: "expense", amount: 68.00,   account: "现金",       category: "购物·服饰鞋包", date: "2026-10-05 14:22", item: "优衣库 T 恤", source: "manual" },
  { id: 110, type: "expense", amount: 9.90,    account: "支付宝余额", category: "餐饮·零食饮料", date: "2026-10-05 10:05", item: "瑞幸咖啡", source: "alipay", counterparty: "瑞幸咖啡" },
  { id: 111, type: "expense", amount: 3500.00, account: "招商储蓄卡", category: "居住·房租", date: "2026-10-01 09:00", item: "10月房租", source: "manual" },
  { id: 112, type: "income",  amount: 58.20,   account: "余额宝",     category: "理财收益", date: "2026-09-30 08:00", item: "余额宝收益", source: "alipay" },
  { id: 113, type: "expense", amount: 156.00,  account: "花呗",       category: "教育·课程培训", date: "2026-09-28 20:15", item: "网课订阅", source: "alipay" },
  { id: 114, type: "transfer", amount: 500.00, from: "微信零钱", to: "现金", category: "", date: "2026-09-27 12:00", item: "提现备用金", source: "manual" },
  { id: 115, type: "expense", amount: 72.00,   account: "交通银行信用卡", category: "交通·火车机票", date: "2026-09-26 07:30", item: "高铁票 杭州东-上海", source: "wechat" },
  { id: 116, type: "expense", amount: 210.00,  account: "支付宝余额", category: "人情往来·红包礼金", date: "2026-09-25 13:20", item: "朋友婚礼红包", source: "alipay" },
  { id: 117, type: "income",  amount: 12500.00, account: "招商储蓄卡", category: "工资薪资", date: "2026-09-10 09:30", item: "9月工资", source: "manual" },
  { id: 118, type: "expense", amount: 430.00,  account: "余额宝",     category: "购物·数码家电", date: "2026-09-12 16:45", item: "机械键盘", source: "manual" },
];

/* 导入批次 */
const BATCHES = [
  { id: 1, platform: "alipay", fileName: "alipay_record_202610.csv", count: 46, amount: 5231.85, status: "confirmed",   date: "2026-10-11 20:30" },
  { id: 2, platform: "wechat", fileName: "wechat_bill_202610.csv",   count: 38, amount: 2876.40, status: "confirmed",   date: "2026-10-11 20:41" },
  { id: 3, platform: "alipay", fileName: "alipay_record_202609.csv", count: 52, amount: 6102.30, status: "rolled_back", date: "2026-10-02 15:10" },
];

/* 导入预览模拟数据（支付宝 CSV 解析结果） */
const IMPORT_PREVIEW = [
  { date: "2026-10-15 12:33", type: "expense", amount: 25.80,  counterparty: "沙县小吃",     cat: "餐饮·三餐",   account: "支付宝余额", dup: false },
  { date: "2026-10-15 09:12", type: "expense", amount: 199.00, counterparty: "天猫超市",     cat: "购物·日用百货", account: "花呗", dup: false },
  { date: "2026-10-14 22:05", type: "expense", amount: 15.00,  counterparty: "哈啰出行",     cat: "交通·打车",   account: "花呗", dup: false },
  { date: "2026-10-14 18:47", type: "income",  amount: 0.85,   counterparty: "余额宝-收益发放", cat: "理财收益",  account: "余额宝", dup: true },
  { date: "2026-10-14 08:30", type: "expense", amount: 6.00,   counterparty: "杭州地铁",     cat: "交通·公交地铁", account: "支付宝余额", dup: false },
  { date: "2026-10-13 21:15", type: "transfer", amount: 1000.00, counterparty: "余额宝-转入", cat: "", account: "", dup: false },
];

/* 近 6 个月收支趋势（月份, 收入, 支出） */
const TREND = [
  { month: "2026-05", income: 13120, expense: 6840 },
  { month: "2026-06", income: 12890, expense: 9210 },
  { month: "2026-07", income: 15020, expense: 7830 },
  { month: "2026-08", income: 12850, expense: 10460 },
  { month: "2026-09", income: 12878, expense: 8972 },
  { month: "2026-10", income: 12878, expense: 6512 },
];

/* 本月支出分类构成（一级分类） */
const EXPENSE_DIST = [
  { name: "居住", value: 3500 }, { name: "餐饮", value: 642 }, { name: "购物", value: 489 },
  { name: "娱乐", value: 300 }, { name: "交通", value: 246 }, { name: "人情往来", value: 210 },
  { name: "教育", value: 156 }, { name: "其他", value: 169 },
];

const fmt = n => "¥" + Number(n).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
