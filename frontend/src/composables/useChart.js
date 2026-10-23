import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * ECharts 实例生命周期组合式函数。
 * 数据到达可能早于图表挂载（首屏并发请求），setOption/on 都做了挂起缓存。
 */
export function useChart() {
  const el = ref(null)
  let chart = null
  let pendingOption = null
  const pendingEvents = []
  const resize = () => { if (chart) chart.resize() }

  onMounted(() => {
    chart = echarts.init(el.value)
    window.addEventListener('resize', resize)
    pendingEvents.forEach(([ev, fn]) => chart.on(ev, fn))
    pendingEvents.length = 0
    if (pendingOption) {
      chart.setOption(pendingOption, true)
      pendingOption = null
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    if (chart) chart.dispose()
    chart = null
  })

  const setOption = opt => {
    if (chart) chart.setOption(opt, true)
    else pendingOption = opt
  }

  const on = (ev, fn) => {
    if (chart) chart.on(ev, fn)
    else pendingEvents.push([ev, fn])
  }

  return { el, setOption, on }
}
