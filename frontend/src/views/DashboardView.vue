<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">净资产</div>
          <div class="stat-value">¥ {{ money(netWorth) }}</div>
          <div class="stat-sub">共 {{ accountCount }} 个账户</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">本月收入</div>
          <div class="stat-value income">¥ {{ money(month.income) }}</div>
          <div class="stat-sub">{{ monthStart }} ~ {{ today }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">本月支出</div>
          <div class="stat-value expense">¥ {{ money(month.expense) }}</div>
          <div class="stat-sub">结余 ¥ {{ money(month.balance) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header><span>近 6 个月收支趋势</span></template>
          <div ref="trendEl" class="chart trend" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header><span>本月支出构成</span></template>
          <div v-show="pieItems.length" ref="pieEl" class="chart pie" />
          <el-empty v-if="!pieItems.length" description="本月暂无支出" :image-size="70" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          <span>最近流水</span>
          <el-button size="small" text type="primary" @click="router.push('/transactions')">查看全部</el-button>
        </div>
      </template>
      <el-table :data="recent" size="small">
        <el-table-column label="时间" width="150" :formatter="({ occurredAt }) => fmtTime(occurredAt)" />
        <el-table-column label="类型" width="76">
          <template #default="{ row }">
            <el-tag size="small"
                    :type="row.type === 'expense' ? 'danger' : row.type === 'income' ? 'success' : 'info'">
              {{ { expense: '支出', income: '收入', transfer: '转账' }[row.type] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ row.categoryName || '未分类' }}</template>
        </el-table-column>
        <el-table-column label="账户" min-width="150">
          <template #default="{ row }">
            {{ row.type === 'transfer'
              ? `${row.fromAccountName || ''} → ${row.toAccountName || ''}`
              : row.type === 'expense' ? (row.fromAccountName || '—') : (row.toAccountName || '—') }}
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <span :class="row.type === 'expense' ? 'expense' : 'income'">{{ money(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="counterparty" label="对方" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterparty || '—' }}</template>
        </el-table-column>
        <template #empty><el-empty description="暂无流水" :image-size="60" /></template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api/request'
import { useChart } from '../composables/useChart'

const router = useRouter()
const netWorth = ref(0)
const accountCount = ref(0)
const month = reactive({ income: 0, expense: 0, balance: 0 })
const recent = ref([])
const pieItems = ref([])

const { el: trendEl, setOption: setTrend } = useChart()
const pieChart = useChart()
const { el: pieEl, setOption: setPie } = pieChart

const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pad = n => String(n).padStart(2, '0')
const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
// 后端 occurredAt 序列化为 ISO "yyyy-MM-ddTHH:mm:ss"（Jackson 默认），归一化为 "yyyy-MM-dd HH:mm" 显示（台账 #13）
const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const now = new Date()
const monthStart = fmtDate(new Date(now.getFullYear(), now.getMonth(), 1))
const today = fmtDate(now)

onMounted(async () => {
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
  await nextTick()
  pieChart.resize()

  setTrend({
    tooltip: { trigger: 'axis', valueFormatter: v => money(v) },
    legend: { data: ['收入', '支出'], bottom: 0 },
    grid: { left: 70, right: 20, top: 20, bottom: 44 },
    xAxis: { type: 'category', data: trend.data.points.map(p => p.label) },
    yAxis: { type: 'value' },
    series: [
      { name: '收入', type: 'line', smooth: true, data: trend.data.points.map(p => Number(p.income)) },
      { name: '支出', type: 'line', smooth: true, data: trend.data.points.map(p => Number(p.expense)) },
    ],
  })
  if (pieItems.value.length) {
    setPie({
      tooltip: { trigger: 'item', valueFormatter: v => money(v) },
      legend: { bottom: 0, type: 'scroll' },
      series: [{
        type: 'pie',
        radius: ['36%', '60%'],
        center: ['50%', '42%'],
        data: pieItems.value.map(i => ({ name: i.name, value: Number(i.amount) })),
        label: { formatter: '{b}\n{d}%' },
      }],
    })
  }
})
</script>

<style scoped>
.stat-card { min-height: 108px; }
.stat-label { color: #8a90a3; font-size: 13px; }
.stat-value { font-size: 26px; font-weight: 700; margin: 8px 0 4px; }
.stat-value.income, .income { color: #2f9e6e; }
.stat-value.expense, .expense { color: #e05252; }
.stat-sub { color: #8a90a3; font-size: 12px; }
.chart.trend { height: 280px; }
.chart.pie { height: 280px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
