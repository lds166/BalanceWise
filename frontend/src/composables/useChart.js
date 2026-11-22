import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * ECharts 实例生命周期组合式函数。
 * 数据到达可能早于图表挂载（首屏并发请求），setOption/on 都做了挂起缓存。
 * 主题相关：setOptionFactory 注册"option 工厂"（内部调用 themeColor/chartTheme
 * 在渲染时解析当前主题色），bw-theme-change 事件触发时用工厂重建 option 换色；
 * 工厂返回 falsy（如数据为空）时跳过渲染，保留图表现状。
 */
export function useChart() {
  const el = ref(null)
  let chart = null
  let pendingOption = null
  let optionFactory = null
  const pendingEvents = []
  const resize = () => { if (chart) chart.resize() }

  const apply = opt => {
    if (chart) chart.setOption(opt, true)
    else pendingOption = opt
  }

  const rerender = () => {
    if (chart && optionFactory) {
      const opt = optionFactory()
      if (opt) apply(opt)
    }
  }

  onMounted(() => {
    chart = echarts.init(el.value)
    window.addEventListener('resize', resize)
    window.addEventListener('bw-theme-change', rerender)
    pendingEvents.forEach(([ev, fn]) => chart.on(ev, fn))
    pendingEvents.length = 0
    if (pendingOption) {
      apply(pendingOption)
      pendingOption = null
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    window.removeEventListener('bw-theme-change', rerender)
    if (chart) chart.dispose()
    chart = null
  })

  const setOption = opt => {
    optionFactory = null
    apply(opt)
  }

  /** 注册 option 工厂并立即渲染；主题切换时自动用工厂重渲染（颜色跟随新主题） */
  const setOptionFactory = fn => {
    optionFactory = fn
    const opt = fn()
    if (opt) apply(opt)
  }

  const on = (ev, fn) => {
    if (chart) chart.on(ev, fn)
    else pendingEvents.push([ev, fn])
  }

  return { el, setOption, setOptionFactory, on, resize }
}
