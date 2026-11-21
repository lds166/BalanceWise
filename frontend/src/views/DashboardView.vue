<template>
  <div class="bw-page" v-loading="loading">
    <!-- 净资产问候大卡 -->
    <section class="hero">
      <div>
        <div class="greet">{{ greeting.text }}，{{ store.user?.nickname || '朋友' }} {{ greeting.emoji }}</div>
        <div class="hero-label">我的净资产</div>
        <div class="hero-value">¥ {{ money(netWorth) }}</div>
        <div class="hero-sub">共 {{ accountCount }} 个账户 · 数据更新到今天</div>
      </div>
      <div class="hero-pig" aria-hidden="true">🐷</div>
    </section>

    <!-- 本月收支 -->
    <section class="stat-row">
      <div class="bw-card stat-card">
        <div class="stat-label"><span class="chip in">➕</span>本月收入</div>
        <div class="stat-value income">¥ {{ money(month.income) }}</div>
        <div class="stat-sub">{{ monthStart }} ~ {{ today }}</div>
      </div>
      <div class="bw-card stat-card">
        <div class="stat-label"><span class="chip out">➖</span>本月支出</div>
        <div class="stat-value expense">¥ {{ money(month.expense) }}</div>
        <div class="stat-sub">结余 <b :class="month.balance >= 0 ? 'income' : 'expense'">¥ {{ money(month.balance) }}</b></div>
      </div>
      <div class="bw-card stat-card cta" @click="router.push('/record')">
        <div class="stat-label"><span class="chip pen">✏️</span>今天记账了吗？</div>
        <div class="cta-text">点这里记一笔 →</div>
        <div class="stat-sub">坚持记录，慢慢变富</div>
      </div>
    </section>

    <!-- 图表 -->
    <div class="chart-grid">
      <div class="bw-card">
        <div class="card-title">📈 近 6 个月收支趋势</div>
        <div v-show="!trendEmpty" ref="trendEl" class="chart trend" />
        <el-empty v-if="trendEmpty" description="记几笔流水，这里就会出现 6 个月的趋势" :image-size="70" />
      </div>
      <div class="bw-card">
        <div class="card-title">🍩 本月支出构成</div>
        <div v-show="pieItems.length" ref="pieEl" class="chart pie" />
        <el-empty v-if="!pieItems.length" description="本月暂无支出，去记一笔吧" :image-size="70" />
      </div>
    </div>

    <!-- 最近流水：手账列表 -->
    <div class="bw-card recent">
      <div class="card-head">
        <span class="card-title">🧾 最近流水</span>
        <el-button size="small" text type="primary" @click="router.push('/transactions')">查看全部</el-button>
      </div>
      <div v-if="recent.length" class="txn-list">
        <div v-for="t in recent" :key="t.id" class="txn" @click="router.push('/transactions')">
          <div class="txn-emoji" :class="t.type">{{ categoryEmoji(t.categoryName, txnKind(t.type)) }}</div>
          <div class="txn-info">
            <div class="txn-title">{{ t.categoryName || typeName(t.type) }}</div>
            <div class="txn-sub">{{ t.item || t.counterparty || typeName(t.type) }} · {{ fmtTime(t.occurredAt) }}</div>
          </div>
          <div class="txn-amt" :class="t.type">
            {{ t.type === 'expense' ? '-' : t.type === 'income' ? '+' : '' }}{{ money(t.amount) }}
          </div>
        </div>
      </div>
      <el-empty v-else description="还没有流水，点右下角「记一笔」开始吧" :image-size="70" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api/request'
import { useChart } from '../composables/useChart'
import { useUserStore } from '../stores/user'
import { categoryEmoji } from '../utils/emoji'
import { themeColor } from '../utils/themeColor'

const router = useRouter()
const store = useUserStore()
const loading = ref(true)
const netWorth = ref(0)
const accountCount = ref(0)
const month = reactive({ income: 0, expense: 0, balance: 0 })
const recent = ref([])
const pieItems = ref([])
const trendPoints = ref([])

const { el: trendEl, setOption: setTrend, resize: resizeTrend } = useChart()
const pieChart = useChart()
const { el: pieEl, setOption: setPie } = pieChart

const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pad = n => String(n).padStart(2, '0')
const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
// 后端 occurredAt 序列化为 ISO "yyyy-MM-ddTHH:mm:ss"（Jackson 默认），归一化为 "yyyy-MM-dd HH:mm" 显示（台账 #13）
const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const typeName = t => ({ expense: '支出', income: '收入', transfer: '转账' }[t] || '流水')
const txnKind = t => (t === 'income' ? 'income' : 'expense')
// 趋势图空态：后端按月补零后若收入支出仍全为 0，用引导空态替代空坐标轴
const trendEmpty = computed(() => trendPoints.value.every(p => !Number(p.income) && !Number(p.expense)))

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return { text: '夜深了', emoji: '🌙' }
  if (h < 9) return { text: '早上好', emoji: '🌅' }
  if (h < 12) return { text: '上午好', emoji: '☀️' }
  if (h < 14) return { text: '中午好', emoji: '🍚' }
  if (h < 18) return { text: '下午好', emoji: '☀️' }
  if (h < 23) return { text: '晚上好', emoji: '🌆' }
  return { text: '夜深了', emoji: '🌙' }
})

const now = new Date()
const monthStart = fmtDate(new Date(now.getFullYear(), now.getMonth(), 1))
const today = fmtDate(now)

onMounted(async () => {
  try {
    await loadDashboard()
  } finally {
    loading.value = false
  }
})

async function loadDashboard() {
  const [acc, sum, trend, pie, txn] = await Promise.all([
    request.get('/accounts'),
    request.get('/stats/summary', { params: { start: monthStart, end: today } }),
    request.get('/stats/trend', { params: { groupBy: 'month', months: 6 } }),
    request.get('/stats/category', { params: { type: 'expense', start: monthStart, end: today } }),
    request.get('/transactions', { params: { size: 10 } }),
  ])
  netWorth.value = acc.data.netWorth
  accountCount.value = acc.data.accounts.length
  Object.assign(month, sum.data)
  recent.value = txn.data.records
  pieItems.value = pie.data.items
  trendPoints.value = trend.data.points
  await nextTick()
  pieChart.resize()
  if (!trendEmpty.value) resizeTrend()

  const cIncome = themeColor('--bw-income', '#3fa37a')
  const cExpense = themeColor('--bw-expense', '#e8684a')
  const cCard = themeColor('--bw-card', '#ffffff')
  if (!trendEmpty.value) {
    setTrend({
      tooltip: { trigger: 'axis', valueFormatter: v => money(v) },
      legend: { data: ['收入', '支出'], bottom: 0 },
      grid: { left: 70, right: 20, top: 20, bottom: 44 },
      xAxis: { type: 'category', data: trend.data.points.map(p => p.label) },
      yAxis: { type: 'value' },
      series: [
        {
          name: '收入', type: 'line', smooth: true,
          symbol: 'circle', symbolSize: 8, lineStyle: { width: 3 },
          itemStyle: { color: cIncome },
          areaStyle: {
            color: {
              type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: cIncome + '40' },
                { offset: 1, color: cIncome + '05' },
              ],
            },
          },
          data: trend.data.points.map(p => Number(p.income)),
        },
        {
          name: '支出', type: 'line', smooth: true,
          symbol: 'circle', symbolSize: 8, lineStyle: { width: 3 },
          itemStyle: { color: cExpense },
          areaStyle: {
            color: {
              type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: cExpense + '38' },
                { offset: 1, color: cExpense + '05' },
              ],
            },
          },
          data: trend.data.points.map(p => Number(p.expense)),
        },
      ],
    })
  }
  if (pieItems.value.length) {
    setPie({
      color: ['#dfa76e', '#eec389', '#d98a76', '#6aa88b', '#c5b0cf', '#8fb0cc', '#dfc48c', '#a9c39c', '#d9a8b2', '#9db0d0'],
      tooltip: { trigger: 'item', valueFormatter: v => money(v) },
      legend: { bottom: 0, type: 'scroll' },
      series: [{
        type: 'pie',
        radius: ['40%', '65%'],
        center: ['50%', '42%'],
        itemStyle: { borderRadius: 6, borderColor: cCard, borderWidth: 3 },
        label: { formatter: '{b}\n{d}%' },
        data: pieItems.value.map(i => ({ name: i.name, value: Number(i.amount) })),
      }],
    })
  }
}
</script>

<style scoped>
.hero {
  position: relative;
  border-radius: var(--bw-radius-lg);
  background: var(--bw-hero-gradient);
  color: #fff;
  padding: 28px 30px;
  box-shadow: var(--bw-shadow-hover);
  overflow: hidden;
}
.greet { font-size: 15px; opacity: 0.92; }
.hero-label { margin-top: 12px; font-size: 13px; opacity: 0.85; }
.hero-value {
  font-size: 40px; font-weight: 800; margin: 4px 0 6px;
  font-variant-numeric: tabular-nums; letter-spacing: 0.5px;
}
.hero-sub { font-size: 12px; opacity: 0.85; }
.hero-pig {
  position: absolute; right: 26px; top: 50%; transform: translateY(-50%);
  font-size: 72px; opacity: 0.9;
  filter: drop-shadow(0 6px 10px rgba(0, 0, 0, 0.18));
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 16px;
}
.stat-card { min-height: 108px; }
.stat-card.cta {
  cursor: pointer;
  border: 2px dashed color-mix(in srgb, var(--bw-primary) 45%, transparent);
  background: var(--bw-primary-soft);
  box-shadow: none;
  transition: transform 0.15s ease, border-color 0.15s ease;
}
.stat-card.cta:hover { transform: translateY(-2px); border-color: var(--bw-primary); }
.stat-label { color: var(--bw-text-secondary); font-size: 13px; display: flex; align-items: center; gap: 8px; }
.chip {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border-radius: 8px; font-size: 13px;
  background: var(--bw-bg);
}
.chip.in { background: var(--bw-income-soft); }
.chip.out { background: var(--bw-expense-soft); }
.chip.pen { background: var(--bw-card); }
.stat-value { font-size: 24px; font-weight: 800; margin: 10px 0 4px; font-variant-numeric: tabular-nums; }
.stat-value.income, .income { color: var(--bw-income); }
.stat-value.expense, .expense { color: var(--bw-expense); }
.stat-sub { color: var(--bw-text-secondary); font-size: 12px; }
.cta-text { font-size: 20px; font-weight: 800; color: var(--bw-primary); margin: 12px 0 6px; }

.chart-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
  margin-top: 16px;
}
.card-title { font-weight: 700; margin-bottom: 8px; }
.chart.trend { height: 280px; }
.chart.pie { height: 280px; }

.recent { margin-top: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }

.txn-list { display: flex; flex-direction: column; }
.txn {
  display: flex; align-items: center; gap: 14px;
  padding: 12px 8px; border-radius: 12px; cursor: pointer;
  transition: background 0.15s ease;
}
.txn:hover { background: var(--bw-bg); }
.txn + .txn { border-top: 1px dashed var(--bw-border); }
.txn-emoji {
  flex: none;
  width: 44px; height: 44px; border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
  font-size: 20px; background: var(--bw-expense-soft);
}
.txn-emoji.income { background: var(--bw-income-soft); }
.txn-emoji.transfer { background: var(--bw-primary-soft); }
.txn-info { flex: 1; min-width: 0; }
.txn-title { font-weight: 600; font-size: 14px; }
.txn-sub {
  color: var(--bw-text-secondary); font-size: 12px; margin-top: 3px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.txn-amt { font-weight: 700; font-variant-numeric: tabular-nums; }
.txn-amt.expense { color: var(--bw-expense); }
.txn-amt.income { color: var(--bw-income); }
.txn-amt.transfer { color: var(--bw-text-secondary); }

@media (max-width: 900px) {
  .stat-row { grid-template-columns: 1fr; }
  .chart-grid { grid-template-columns: 1fr; }
  .hero-pig { display: none; }
}
</style>
