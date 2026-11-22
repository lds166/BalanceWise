<template>
  <div class="bw-page">
    <div class="bw-page-title">统计分析</div>
    <div class="bw-card" style="margin-bottom: 16px">
      <div class="filter-bar">
        <el-radio-group v-model="preset" @change="loadAll">
          <el-radio-button value="month">本月</el-radio-button>
          <el-radio-button value="lastMonth">上月</el-radio-button>
          <el-radio-button value="year">今年</el-radio-button>
          <el-radio-button value="custom">自定义</el-radio-button>
        </el-radio-group>
        <el-date-picker v-if="preset === 'custom'" v-model="customRange" type="daterange"
                        value-format="YYYY-MM-DD" range-separator="~"
                        start-placeholder="开始日期" end-placeholder="结束日期"
                        style="width: 250px" @change="loadAll" />
        <el-select v-model="accountId" filterable clearable placeholder="全部账户"
                   style="width: 160px" @change="loadAll">
          <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
        </el-select>
        <el-radio-group v-model="granularity" @change="loadAll">
          <el-radio-button value="month">按月</el-radio-button>
          <el-radio-button value="day">按日</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">收入合计</div>
          <div class="stat-value income">¥ {{ money(summary.income) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">支出合计</div>
          <div class="stat-value expense">¥ {{ money(summary.expense) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">结余</div>
          <div class="stat-value" :class="Number(summary.balance) < 0 ? 'expense' : 'income'">
            ¥ {{ money(summary.balance) }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header><span>收支趋势（{{ granularity === 'month' ? '按月' : '按日' }}）</span></template>
          <div v-show="!trendEmpty" ref="trendEl" class="chart lg" />
          <el-empty v-if="trendEmpty" description="该区间暂无收支数据" :image-size="70" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>
            <div class="card-head">
              <el-radio-group v-model="catType" size="small" @change="loadCategory">
                <el-radio-button value="expense">支出构成</el-radio-button>
                <el-radio-button value="income">收入构成</el-radio-button>
              </el-radio-group>
              <el-button v-if="drill" size="small" text type="primary" @click="drill = null">← 返回一级</el-button>
            </div>
          </template>
          <div v-show="catItems.length" class="drill-hint">提示：点击扇区可下钻查看子分类</div>
          <div v-show="catItems.length" ref="pieEl" class="chart lg" />
          <el-empty v-if="!catItems.length" description="该区间暂无数据" :image-size="70" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header><span>账户余额对比<span class="snap-note">（当前快照，不随筛选变化）</span></span></template>
          <div v-show="accSummary.accounts.length" ref="barEl" class="chart md" />
          <el-empty v-if="!accSummary.accounts.length" description="还没有账户数据" :image-size="70" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header><span>资产 / 负债占比</span></template>
          <div v-show="!ratioEmpty" ref="ratioEl" class="chart md" />
          <el-empty v-if="ratioEmpty" description="还没有账户数据，先去建一个账户吧" :image-size="70" />
          <div class="ratio-text">
            净资产 ¥ {{ money(accSummary.netWorth) }}<br>
            资产合计 ¥ {{ money(accSummary.assetTotal) }} ／ 负债合计 ¥ {{ money(accSummary.liabilityTotal) }}
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onActivated, onMounted, reactive, ref, watch } from 'vue'
import request from '../api/request'
import { useChart } from '../composables/useChart'
import { themeColor } from '../utils/themeColor'
import { chartTheme } from '../utils/chartTheme'

// ===== 多维筛选（UC-5.7）=====
const preset = ref('month')
const customRange = ref(null)
const accountId = ref(null)
const granularity = ref('month')
const accounts = ref([])

const summary = reactive({ income: 0, expense: 0, balance: 0 })
const catType = ref('expense')
const catItems = ref([])
const drill = ref(null)
const accSummary = reactive({ netWorth: 0, assetTotal: 0, liabilityTotal: 0, accounts: [] })
// 趋势图空态：区间补零后收支仍全为 0 时，用空态替代空坐标轴
const trendPoints = ref([])
const trendEmpty = computed(() => trendPoints.value.every(p => !Number(p.income) && !Number(p.expense)))
// 资产/负债占比空态：资产与负债合计均为 0（无账户）时展示引导
const ratioEmpty = computed(() => !Number(accSummary.assetTotal) && !Number(accSummary.liabilityTotal))

const { el: trendEl, setOptionFactory: setTrend, resize: resizeTrend } = useChart()
const { el: pieEl, setOptionFactory: setPie, on: onPie, resize: resizePie } = useChart()
const { el: barEl, setOptionFactory: setBar, resize: resizeBar } = useChart()
const { el: ratioEl, setOptionFactory: setRatio, resize: resizeRatio } = useChart()

const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pad = n => String(n).padStart(2, '0')
const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

const range = computed(() => {
  const now = new Date()
  if (preset.value === 'month') {
    return { start: fmtDate(new Date(now.getFullYear(), now.getMonth(), 1)), end: fmtDate(now) }
  }
  if (preset.value === 'lastMonth') {
    const last = new Date(now.getFullYear(), now.getMonth(), 0)
    return { start: fmtDate(new Date(last.getFullYear(), last.getMonth(), 1)), end: fmtDate(last) }
  }
  if (preset.value === 'year') {
    return { start: fmtDate(new Date(now.getFullYear(), 0, 1)), end: fmtDate(now) }
  }
  const r = customRange.value
  return r && r.length === 2
    ? { start: r[0], end: r[1] }
    : { start: fmtDate(new Date(now.getFullYear(), now.getMonth(), 1)), end: fmtDate(now) }
})

const baseParams = computed(() => ({
  start: range.value.start,
  end: range.value.end,
  ...(accountId.value ? { accountId: accountId.value } : {}),
}))

onMounted(async () => {
  const res = await request.get('/accounts')
  accounts.value = res.data.accounts
  Object.assign(accSummary, {
    netWorth: res.data.netWorth,
    assetTotal: res.data.assetTotal,
    liabilityTotal: res.data.liabilityTotal,
    accounts: res.data.accounts,
  })
  // 下钻：点击一级扇区进入二级（UC-5.5 可选下钻）
  onPie('click', params => {
    if (drill.value) return
    const item = catItems.value.find(i => i.name === params.name)
    if (item && item.children && item.children.length) drill.value = item
  })
  renderAccountsOverview()
  await loadAll()
})

// keep-alive 重访：静默刷新图表数据（图表留在原地更新，不重播动画不闪屏）；首次激活跳过
let statsFirstActivate = true
onActivated(() => {
  if (statsFirstActivate) {
    statsFirstActivate = false
    return
  }
  loadAll()
})

async function loadAll() {
  drill.value = null
  await Promise.all([loadSummary(), loadTrend(), loadCategory()])
}

async function loadSummary() {
  const res = await request.get('/stats/summary', { params: baseParams.value })
  Object.assign(summary, res.data)
}

async function loadTrend() {
  const res = await request.get('/stats/trend', {
    params: { ...baseParams.value, groupBy: granularity.value },
  })
  const pts = res.data.points
  trendPoints.value = pts
  if (trendEmpty.value) return
  // option 工厂：渲染时解析当前主题令牌，切主题自动重渲染；单周期数据退化为柱状（折线一个点没有信息量）
  setTrend(() => {
    const ct = chartTheme()
    const single = pts.length <= 1
    return {
      tooltip: { trigger: 'axis', valueFormatter: v => money(v) },
      legend: { ...ct.legendText, data: ['收入', '支出', '结余'], bottom: 0 },
      grid: { left: 70, right: 20, top: 20, bottom: 46 },
      xAxis: { type: 'category', ...ct.categoryAxis, data: pts.map(p => p.label) },
      yAxis: { type: 'value', ...ct.valueAxis },
      series: [
        { name: '收入', type: single ? 'bar' : 'line', smooth: !single, lineStyle: { width: 3 }, symbol: 'circle', symbolSize: 7,
          barMaxWidth: 44, itemStyle: { color: themeColor('--bw-income', '#3fa37a') }, data: pts.map(p => Number(p.income)) },
        { name: '支出', type: single ? 'bar' : 'line', smooth: !single, lineStyle: { width: 3 }, symbol: 'circle', symbolSize: 7,
          barMaxWidth: 44, itemStyle: { color: themeColor('--bw-expense', '#e8684a') }, data: pts.map(p => Number(p.expense)) },
        { name: '结余', type: single ? 'bar' : 'line', smooth: !single, lineStyle: { width: 2, type: 'dashed' }, symbol: 'none',
          barMaxWidth: 44, itemStyle: { color: themeColor('--bw-primary', '#f28c38') }, data: pts.map(p => Number(p.balance)) },
      ],
    }
  })
  // 空态→有数据翻转时容器由隐藏转可见，DOM 更新后 resize 保证尺寸正确
  nextTick(resizeTrend)
}

async function loadCategory() {
  drill.value = null // 切换支出/收入时重置下钻态，避免渲染旧类型的二级数据
  const res = await request.get('/stats/category', {
    params: { ...baseParams.value, type: catType.value },
  })
  catItems.value = res.data.items
  renderPie()
}

const PIE_PALETTE = ['#dfa76e', '#eec389', '#d98a76', '#6aa88b', '#c5b0cf', '#8fb0cc', '#dfc48c', '#a9c39c', '#d9a8b2', '#9db0d0']

function renderPie() {
  const source = drill.value ? drill.value.children : catItems.value
  if (!source.length) return
  setPie(() => {
    const ct = chartTheme()
    return {
      color: PIE_PALETTE,
      tooltip: { trigger: 'item', valueFormatter: v => money(v) },
      series: [{
        type: 'pie',
        radius: ['35%', '60%'],
        center: ['50%', '50%'],
        itemStyle: { borderRadius: 6, borderColor: themeColor('--bw-card', '#ffffff'), borderWidth: 3 },
        data: source.map(i => ({ name: i.name, value: Number(i.amount) })),
        label: { formatter: '{b}\n{d}%', color: ct.legendText.textStyle.color },
        // 小扇区标签防重叠：小于 6°（约 1.7%）不出标签，仍重叠的自动隐藏
        minShowLabelAngle: 6,
        labelLayout: { hideOverlap: true },
      }],
    }
  })
  // v-show 隐藏容器初始化为 0×0，数据到达显示后需 nextTick + resize（Task 6 评审已知修正）
  nextTick(resizePie)
}

watch(drill, renderPie)

/** 账户总览（UC-5.6）：数据源为 GET /api/accounts（P1 已提供），当前快照不随时间筛选变化 */
function renderAccountsOverview() {
  const items = accSummary.accounts
  setBar(() => {
    const ct = chartTheme()
    return {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: v => money(v) },
      grid: { left: 90, right: 40, top: 10, bottom: 26 },
      xAxis: { type: 'value', ...ct.valueAxis },
      yAxis: { type: 'category', ...ct.categoryAxis, data: items.map(a => a.name) },
      series: [{
        type: 'bar',
        barMaxWidth: 22,
        data: items.map(a => Number(a.balance)),
        itemStyle: { color: p => (p.value < 0 ? themeColor('--bw-expense', '#e8684a') : themeColor('--bw-primary', '#f28c38')), borderRadius: [0, 6, 6, 0] },
        label: { show: true, position: 'right', fontSize: 11, color: ct.legendText.textStyle.color, formatter: p => money(p.value) },
      }],
    }
  })
  // 账户数据到达使 v-show 翻转可见后，DOM 更新后 resize 保证尺寸正确
  nextTick(() => { resizeBar(); if (!ratioEmpty.value) resizeRatio() })
  if (ratioEmpty.value) return
  setRatio(() => {
    const ct = chartTheme()
    return {
      tooltip: { trigger: 'item', valueFormatter: v => money(v) },
      legend: { ...ct.legendText, bottom: 0 },
      series: [{
        type: 'pie',
        radius: ['35%', '60%'],
        center: ['50%', '42%'],
        data: [
          { name: '资产', value: Number(accSummary.assetTotal), itemStyle: { color: themeColor('--bw-income', '#3fa37a') } },
          { name: '负债', value: Number(accSummary.liabilityTotal), itemStyle: { color: themeColor('--bw-expense', '#e8684a') } },
        ],
        label: { formatter: '{b}\n{d}%', color: ct.legendText.textStyle.color },
      }],
    }
  })
}
</script>

<style scoped>
.filter-bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.stat-card { min-height: 96px; }
.stat-label { color: var(--bw-text-secondary); font-size: 13px; }
.stat-value { font-size: 26px; font-weight: 700; margin-top: 8px; }
.stat-value.income, .income { color: var(--bw-income); }
.stat-value.expense, .expense { color: var(--bw-expense); }
.chart.lg { height: 320px; }
.chart.md { height: 260px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.ratio-text { color: var(--bw-text-secondary); font-size: 12px; line-height: 1.9; text-align: center; padding-bottom: 6px; }
.snap-note { color: var(--bw-text-secondary); font-size: 12px; }
.drill-hint { color: var(--bw-text-secondary); font-size: 12px; margin-bottom: 6px; }
</style>
