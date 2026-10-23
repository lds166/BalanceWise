// 读取当前主题的 CSS 变量值（供 ECharts 等 canvas 场景使用，canvas 不支持 var() 字符串）
export function themeColor(varName, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(varName).trim()
  return v || fallback
}
