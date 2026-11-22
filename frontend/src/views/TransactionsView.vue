<template>
  <div class="bw-page">
    <!-- 头部：月份切换 + 当月汇总 + 工具 -->
    <div class="bw-card head-card">
      <div class="head-row">
        <div class="month-nav">
          <el-button circle text @click="shiftMonth(-1)"><el-icon><ArrowLeft /></el-icon></el-button>
          <span class="month-label">{{ monthLabel }}</span>
          <el-button circle text :disabled="atCurrentMonth" @click="shiftMonth(1)">
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
        <div class="month-summary">
          <span class="sum-item">收入 <b class="amt-income">{{ money(summary.income) }}</b></span>
          <span class="sum-item">支出 <b class="amt-expense">{{ money(summary.expense) }}</b></span>
          <span class="sum-item">结余 <b>{{ money(summary.balance) }}</b></span>
        </div>
        <div class="head-actions">
          <el-badge :value="activeFilterCount" :hidden="!activeFilterCount" type="primary">
            <el-button @click="filterOpen = !filterOpen">
              <el-icon><Filter /></el-icon>&nbsp;筛选
            </el-button>
          </el-badge>
          <el-button @click="exportCsv">导出 CSV</el-button>
        </div>
      </div>

      <el-collapse-transition>
        <div v-show="filterOpen" class="filters">
          <el-select v-model="query.type" placeholder="类型" clearable style="width: 110px">
            <el-option label="支出" value="expense" />
            <el-option label="收入" value="income" />
            <el-option label="转账" value="transfer" />
          </el-select>
          <el-select v-model="query.accountId" placeholder="账户" clearable filterable style="width: 150px">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <el-cascader
            v-model="query.categoryId" :options="categoryOptions" :props="cascaderProps"
            filterable clearable placeholder="分类" style="width: 170px"
          />
          <el-input v-model="query.keyword" placeholder="对方/备注关键字" clearable style="width: 180px" @keyup.enter="applyFilters" />
          <el-button type="primary" @click="applyFilters">查询</el-button>
          <el-button v-if="activeFilterCount" @click="resetFilters">清除筛选</el-button>
        </div>
      </el-collapse-transition>
    </div>

    <!-- 日记账本：按天分组 -->
    <div class="bw-card list-card" v-loading="loading">
      <template v-for="g in groups" :key="g.date">
        <div class="day-head">
          <span class="day-date">{{ g.label }}</span>
          <span class="day-sum">{{ g.sumText }}</span>
        </div>
        <div v-for="r in g.items" :key="r.id" class="tx-row">
          <span class="tx-icon">{{ categoryEmoji(r.categoryName, r.type) }}</span>
          <div class="tx-main">
            <div class="tx-title">
              {{ r.type === 'transfer' ? '转账' : (r.categoryName || '未分类') }}
              <el-tooltip v-if="r.source !== 'manual'" :content="'来源：' + sourceLabel(r.source)" placement="top">
                <el-icon class="src-dot"><Download /></el-icon>
              </el-tooltip>
            </div>
            <div class="tx-sub">{{ subOf(r) }}<span class="sub-acc"> · {{ accountText(r) }}</span></div>
          </div>
          <span class="tx-acc">{{ accountText(r) }}</span>
          <span class="tx-amt" :class="amountClass(r)">{{ amountText(r) }}</span>
          <div class="tx-ops">
            <el-button size="small" text @click.stop="openEdit(r)">编辑</el-button>
            <el-button size="small" text type="danger" @click.stop="remove(r)">删除</el-button>
          </div>
        </div>
      </template>

      <el-empty v-if="!groups.length && !loading" class="list-empty"
                :description="hasFilter ? '当前筛选条件下没有流水' : '本月还没有流水，点右下角「记一笔」开始吧'" :image-size="70">
        <el-button v-if="hasFilter" size="small" @click="resetFilters">清除筛选条件</el-button>
      </el-empty>
      <div v-if="total > rows.length" class="trunc-hint">当月流水超过 {{ rows.length }} 笔，仅显示前 {{ rows.length }} 笔，可用筛选缩小范围</div>
      <div v-if="rows.length" class="list-foot">共 {{ total }} 笔</div>
    </div>

    <el-dialog v-model="dlg.visible" title="编辑流水" width="520px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="类型">
          <el-radio-group v-model="dlg.form.type">
            <el-radio-button value="expense">支出</el-radio-button>
            <el-radio-button value="income">收入</el-radio-button>
            <el-radio-button value="transfer">转账</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="dlg.form.type !== 'income'" :label="dlg.form.type === 'transfer' ? '转出账户' : '账户'">
          <el-select v-model="dlg.form.fromAccountId" filterable style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dlg.form.type !== 'expense'" :label="dlg.form.type === 'transfer' ? '转入账户' : '账户'">
          <el-select v-model="dlg.form.toAccountId" filterable style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dlg.form.type !== 'transfer'" label="分类">
          <el-cascader
            v-model="dlg.form.categoryId" :options="dlgCategoryOptions" :props="cascaderProps"
            filterable clearable placeholder="未分类" style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="dlg.form.amount" :min="0.01" :max="99999999" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="dlg.form.occurredAt" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item label="对方">
          <el-input v-model="dlg.form.counterparty" maxlength="128" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dlg.form.item" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onActivated, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Download, Filter } from '@element-plus/icons-vue'
import request from '../api/request'
import { categoryEmoji } from '../utils/emoji'

const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: false }

// 月份导航：默认当前月，翻月查看（替代旧日期范围 + 分页器）
const cur = ref(new Date())
const monthLabel = computed(() => `${cur.value.getFullYear()}年${cur.value.getMonth() + 1}月`)
const atCurrentMonth = computed(() => {
  const now = new Date()
  return cur.value.getFullYear() === now.getFullYear() && cur.value.getMonth() === now.getMonth()
})
const monthStart = computed(() => {
  const d = cur.value
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
})
const monthEnd = computed(() => {
  const d = new Date(cur.value.getFullYear(), cur.value.getMonth() + 1, 0)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

function shiftMonth(delta) {
  cur.value = new Date(cur.value.getFullYear(), cur.value.getMonth() + delta, 1)
  load()
}

const query = reactive({ type: null, accountId: null, categoryId: null, keyword: '' })
const filterOpen = ref(false)
// 类型与分类联动：切换类型后清空已选分类，避免「收入类型 + 支出分类」恒查空
let restoringFilters = false
watch(() => query.type, () => { if (!restoringFilters) query.categoryId = null })
const categoryOptions = computed(() => {
  if (query.type === 'income') return incomeTree.value
  if (query.type === 'expense') return expenseTree.value
  return [...expenseTree.value, ...incomeTree.value]
})
// 编辑对话框的分类选项跟表单类型走
const dlgCategoryOptions = computed(() =>
  dlg.form.type === 'income' ? incomeTree.value : expenseTree.value
)

const TYPE_LABEL = { expense: '支出', income: '收入', transfer: '转账' }
const SOURCE_LABEL = { manual: '手工', alipay: '支付宝', wechat: '微信', reconcile: '校正' }
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const typeLabel = t => TYPE_LABEL[t] || t
const sourceLabel = s => SOURCE_LABEL[s] || s || '—'
const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const accountText = r =>
  r.type === 'transfer'
    ? `${r.fromAccountName || ''} → ${r.toAccountName || ''}`
    : r.type === 'expense' ? (r.fromAccountName || '—') : (r.toAccountName || '—')
const amountText = r =>
  r.type === 'expense' ? `-¥${money(r.amount)}`
  : r.type === 'income' ? `+¥${money(r.amount)}` : `¥${money(r.amount)}`
const amountClass = r => (r.type === 'expense' ? 'amt-expense' : r.type === 'income' ? 'amt-income' : '')

// 行第二行：备注/对方 + 时间（按天分组后组头已有日期，行内只补时分与来源上下文）
const subOf = r => {
  const parts = []
  if (r.item) parts.push(r.item)
  else if (r.counterparty) parts.push(r.counterparty)
  if (r.occurredAt) parts.push(String(r.occurredAt).replace('T', ' ').slice(11, 16))
  return parts.join(' · ') || '—'
}

// 按天分组 + 日小计（后端按 occurredAt 倒序返回，分组保持顺序）
const groups = computed(() => {
  const list = []
  const map = new Map()
  for (const r of rows.value) {
    const date = r.occurredAt ? String(r.occurredAt).slice(0, 10) : '—'
    let g = map.get(date)
    if (!g) {
      g = { date, items: [], income: 0, expense: 0, transfers: 0 }
      map.set(date, g)
      list.push(g)
    }
    g.items.push(r)
    if (r.type === 'expense') g.expense += Number(r.amount)
    else if (r.type === 'income') g.income += Number(r.amount)
    else g.transfers += 1
  }
  return list.map(g => {
    const d = new Date(g.date + 'T00:00:00')
    const label = `${d.getMonth() + 1}月${d.getDate()}日 ${WEEKDAYS[d.getDay()]}`
    const parts = []
    if (g.expense) parts.push(`支出 ¥${money(g.expense)}`)
    if (g.income) parts.push(`收入 ¥${money(g.income)}`)
    if (g.transfers) parts.push(`转账 ${g.transfers} 笔`)
    return { ...g, label, sumText: parts.join(' · ') || `${g.items.length} 笔` }
  })
})

// 当月汇总（转账不计入收支）
const summary = computed(() => {
  let income = 0, expense = 0
  for (const r of rows.value) {
    if (r.type === 'income') income += Number(r.amount)
    else if (r.type === 'expense') expense += Number(r.amount)
  }
  return { income, expense, balance: income - expense }
})

// 空态区分：带筛选条件查询为空时，提示并提供一键清除
const activeFilterCount = computed(() =>
  ['type', 'accountId', 'categoryId', 'keyword'].reduce((n, k) => n + (query[k] ? 1 : 0), 0)
)
const hasFilter = computed(() => activeFilterCount.value > 0)

onMounted(async () => {
  const [acc, exp, inc] = await Promise.all([
    request.get('/accounts'),
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  accounts.value = acc.data.accounts
  expenseTree.value = exp.data
  incomeTree.value = inc.data
  restoreFilters()
  await load()
})

// keep-alive 重访：静默刷新（遮罩盖在缓存的日记账上，不闪屏）；首次激活与 onMounted 重叠跳过
let txFirstActivate = true
onActivated(() => {
  if (txFirstActivate) {
    txFirstActivate = false
    return
  }
  load()
})

function params() {
  return {
    page: 1, size: 500,
    type: query.type || undefined,
    accountId: query.accountId || undefined,
    categoryId: query.categoryId || undefined,
    keyword: query.keyword || undefined,
    start: monthStart.value, end: monthEnd.value,
  }
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/transactions', { params: params() })
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  filterOpen.value = false
  persistFilters()
  load()
}

// 筛选条件持久化到 localStorage：刷新或从其他页面返回后可恢复（月份为浏览态不持久化）
const FILTERS_KEY = 'bw-tx-filters'
function persistFilters() {
  localStorage.setItem(FILTERS_KEY, JSON.stringify({
    type: query.type, accountId: query.accountId, categoryId: query.categoryId, keyword: query.keyword,
  }))
}

function restoreFilters() {
  try {
    const saved = JSON.parse(localStorage.getItem(FILTERS_KEY) || 'null')
    if (!saved) return
    restoringFilters = true
    if (saved.type) query.type = saved.type
    if (saved.accountId && accounts.value.some(a => a.id === saved.accountId)) query.accountId = saved.accountId
    if (saved.categoryId) query.categoryId = saved.categoryId
    if (saved.keyword) query.keyword = saved.keyword
  } catch {
    localStorage.removeItem(FILTERS_KEY)
  } finally {
    // watch 默认 flush: 'pre'，先于 nextTick 回调执行，复位前标志仍有效（同 openEdit 模式）
    nextTick(() => { restoringFilters = false })
  }
}

function resetFilters() {
  Object.assign(query, { type: null, accountId: null, categoryId: null, keyword: '' })
  localStorage.removeItem(FILTERS_KEY)
  load()
}

// Date → 本地 'yyyy-MM-ddTHH:mm:ss'
function fmtLocal(d) {
  if (!d) return null
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const dlg = reactive({
  visible: false, saving: false, id: null,
  form: { type: 'expense', fromAccountId: null, toAccountId: null, categoryId: null,
          amount: null, occurredAt: null, counterparty: '', item: '' },
})

// 打开编辑预填时标记，避免类型 watch 误清预填的分类
let openingEdit = false

// 类型变化时清掉不适用的分类
watch(() => dlg.form.type, () => {
  if (openingEdit) return
  dlg.form.categoryId = null
})

function openEdit(row) {
  openingEdit = true
  Object.assign(dlg, {
    id: row.id, visible: true,
    form: {
      type: row.type,
      fromAccountId: row.fromAccountId,
      toAccountId: row.toAccountId,
      categoryId: row.categoryId,
      amount: Number(row.amount),
      occurredAt: row.occurredAt ? new Date(row.occurredAt) : null,
      counterparty: row.counterparty || '',
      item: row.item || '',
    },
  })
  // watch 默认 flush: 'pre'，先于 nextTick 回调执行，复位前标志仍有效
  nextTick(() => { openingEdit = false })
}

async function saveEdit() {
  const f = dlg.form
  dlg.saving = true
  try {
    await request.put(`/transactions/${dlg.id}`, {
      type: f.type, amount: f.amount,
      occurredAt: fmtLocal(f.occurredAt),
      counterparty: f.counterparty || null, item: f.item || null,
      categoryId: f.type === 'transfer' ? null : f.categoryId,
      fromAccountId: f.type === 'income' ? null : f.fromAccountId,
      toAccountId: f.type === 'expense' ? null : f.toAccountId,
    })
    ElMessage.success('已保存')
    dlg.visible = false
    await load()
  } finally {
    dlg.saving = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除该笔${typeLabel(row.type)}（${amountText(row)}）？删除后余额将自动冲销，此操作不可恢复。`,
      '删除确认', { type: 'warning' })
  } catch { return }
  await request.delete(`/transactions/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

function filenameDate() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function exportCsv() {
  const res = await request.get('/transactions/export', { params: params(), responseType: 'blob' })
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = `transactions_${filenameDate()}.csv`
  document.body.appendChild(a)   // Firefox 需要挂到 DOM
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)  // 延迟释放，避免截断下载
}
</script>

<style scoped>
.head-card { margin-bottom: 16px; padding: 14px 20px; }
.head-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.month-nav { display: flex; align-items: center; gap: 4px; }
.month-label { font-size: 16px; font-weight: 700; min-width: 96px; text-align: center; }
.month-summary { display: flex; gap: 16px; flex: 1; flex-wrap: wrap; }
.sum-item { color: var(--bw-text-secondary); font-size: 13px; }
.sum-item b { font-variant-numeric: tabular-nums; }
.head-actions { display: flex; gap: 10px; align-items: center; }
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--bw-border); }

.list-card { padding: 0 20px 12px; }
.day-head {
  position: sticky; top: 57px; z-index: 5;
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 2px 8px; background: var(--bw-card);
  border-bottom: 1px dashed var(--bw-border);
  font-size: 13px;
}
.day-date { font-weight: 700; color: var(--bw-text); }
.day-sum { color: var(--bw-text-secondary); font-variant-numeric: tabular-nums; }

.tx-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 2px; border-bottom: 1px solid var(--bw-border);
}
.tx-row:last-of-type { border-bottom: none; }
.tx-row:hover { background: var(--bw-bg); }
.tx-icon {
  flex: none; width: 38px; height: 38px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: var(--bw-primary-soft); font-size: 18px;
}
.tx-main { flex: 1; min-width: 0; }
.tx-title { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 4px; }
.tx-sub { font-size: 12px; color: var(--bw-text-secondary); margin-top: 2px;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.src-dot { font-size: 12px; color: var(--bw-text-secondary); }
.tx-acc { flex: none; width: 130px; text-align: right; font-size: 12px; color: var(--bw-text-secondary); }
.sub-acc { display: none; }
.tx-amt { flex: none; width: 110px; text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }
.tx-ops { flex: none; display: flex; opacity: 0; transition: opacity 0.15s ease; }
.tx-row:hover .tx-ops { opacity: 1; }
.amt-expense { color: var(--bw-expense); }
.amt-income { color: var(--bw-income); }
.list-empty { padding: 40px 0; }
.list-foot { text-align: center; color: var(--bw-text-secondary); font-size: 12px; padding: 12px 0 4px; }
.trunc-hint { text-align: center; color: var(--bw-text-secondary); font-size: 12px; padding: 10px 0 0; }

@media (max-width: 760px) {
  /* 手机端：账户并入第二行（.sub-acc），腾出宽度给标题；触屏无 hover，操作常显 */
  .sub-acc { display: inline; }
  .tx-acc { display: none; }
  .tx-ops { opacity: 1; }
  .tx-ops .el-button { padding: 4px 6px; font-size: 12px; }
  .tx-amt { width: auto; }
  .month-summary { gap: 10px; }
}
</style>
