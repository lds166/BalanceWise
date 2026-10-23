// 分类名 → emoji 映射：命中关键词即返回对应图标，未命中按收支回退
const EXPENSE_MAP = [
  [/餐|饭|美食|早餐|午餐|晚餐|夜宵|饮料|咖啡|奶茶|零食|水果/i, '🍜'],
  [/交通|出行|打车|公交|地铁|加油|停车|高铁|火车|机票|滴滴/i, '🚌'],
  [/购物|服饰|衣服|鞋|包|美妆|护肤|数码|日用|百货/i, '🛍️'],
  [/住房|房租|房贷|物业|水电|燃气|家居|装修/i, '🏠'],
  [/娱乐|游戏|电影|视频|会员|旅游|旅行|健身|运动/i, '🎮'],
  [/医疗|药|医院|看病|健康|体检/i, '💊'],
  [/教育|书|课程|学习|培训|学费|考试/i, '📚'],
  [/宠物|猫|狗/i, '🐱'],
  [/通讯|话费|宽带|手机/i, '📱'],
  [/红包|礼金|人情|随礼|礼物/i, '🧧'],
  [/孩子|baby|奶粉|尿不湿|玩具/i, '👶'],
]

const INCOME_MAP = [
  [/工资|薪资|薪水|月薪/i, '💼'],
  [/奖金|绩效|年终奖|提成/i, '🎉'],
  [/兼职|副业|外快|稿费/i, '⛏️'],
  [/理财|利息|股息|基金|股票|分红/i, '📈'],
  [/红包|转账/i, '🧧'],
  [/退款|报销|返现/i, '↩️'],
  [/租金|房租收入/i, '🏠'],
]

/**
 * 根据分类名和类型返回 emoji
 * @param {string} name 分类名
 * @param {string} [type] expense / income
 * @returns {string}
 */
export function categoryEmoji(name, type = 'expense') {
  const text = String(name || '')
  if (type === 'income') {
    for (const [re, emoji] of INCOME_MAP) if (re.test(text)) return emoji
    return '💰'
  }
  for (const [re, emoji] of EXPENSE_MAP) if (re.test(text)) return emoji
  return '💸'
}

/** 交易类型对应 emoji */
export function typeEmoji(type) {
  return { expense: '➖', income: '➕', transfer: '🔄' }[type] || '📄'
}
