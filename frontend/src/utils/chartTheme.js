import { themeColor } from './themeColor'

/**
 * ECharts 通用主题片段：文本/轴/图例颜色跟随 bw 主题令牌。
 * canvas 不支持 var() 字符串，需在 setOption 时解析；
 * 切换主题后由 useChart 的 bw-theme-change 监听调用 option 工厂重渲染。
 */
export function chartTheme() {
  const text = themeColor('--bw-text-secondary', '#8d9099')
  const border = themeColor('--bw-border', '#f1e9db')
  return {
    legendText: { textStyle: { color: text } },
    categoryAxis: {
      axisLine: { lineStyle: { color: border } },
      axisTick: { show: false },
      axisLabel: { color: text },
      splitLine: { show: false },
    },
    valueAxis: {
      axisLine: { show: false },
      axisLabel: { color: text },
      splitLine: { lineStyle: { color: border } },
    },
  }
}
