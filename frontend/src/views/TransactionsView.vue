<template>
  <div class="bw-page">
    <div class="bw-card" style="margin-bottom: 16px">
      <div class="filters">
        <el-date-picker
          v-model="range" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px"
        />
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
        <el-input v-model="query.keyword" placeholder="对方/备注关键字" clearable style="width: 180px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-button @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>

    <div class="bw-card">
      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <el-table-column prop="occurredAt" label="时间" width="165" :formatter="fmtTimeCol" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.type)">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ row.categoryName || '—' }}</template>
        </el-table-column>
        <el-table-column label="账户" min-width="150">
          <template #default="{ row }">{{ accountText(row) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <span :class="amountClass(row)">{{ amountText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="counterparty" label="对方" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterparty || '—' }}</template>
        </el-table-column>
        <el-table-column prop="item" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.item || '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="hasFilter ? '当前筛选条件下没有流水' : '暂无流水'" :image-size="60">
            <el-button v-if="hasFilter" size="small" @click="resetFilters">清除筛选条件</el-button>
          </el-empty>
        </template>
      </el-table>
      <el-pagination
        v-model:current-page="query.page" v-model:page-size="query.size"
        :total="total" :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load" @size-change="search"
      />
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
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const range = ref(null)
const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: false }

const query = reactive({ page: 1, size: 20, type: null, accountId: null, categoryId: null, keyword: '' })
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

const typeLabel = t => TYPE_LABEL[t] || t
const tagType = t => (t === 'expense' ? 'danger' : t === 'income' ? 'success' : 'info')
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
// 时间列归一化：后端 ISO "yyyy-MM-ddTHH:mm:ss" → "yyyy-MM-dd HH:mm"（与仪表盘口径一致）
const fmtTimeCol = (row, col, v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '—')
// 空态区分：带筛选条件查询为空时，提示并提供一键清除
const hasFilter = computed(() => !!(query.type || query.accountId || query.categoryId || query.keyword || range.value))

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

function params() {
  return {
    page: query.page, size: query.size,
    type: query.type || undefined,
    accountId: query.accountId || undefined,
    categoryId: query.categoryId || undefined,
    keyword: query.keyword || undefined,
    start: range.value?.[0], end: range.value?.[1],
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

function search() {
  query.page = 1
  persistFilters()
  load()
}

// 筛选条件持久化到 localStorage：刷新或从其他页面返回后可恢复
const FILTERS_KEY = 'bw-tx-filters'
function persistFilters() {
  localStorage.setItem(FILTERS_KEY, JSON.stringify({
    type: query.type, accountId: query.accountId, categoryId: query.categoryId,
    keyword: query.keyword, start: range.value?.[0] ?? null, end: range.value?.[1] ?? null,
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
    if (saved.start && saved.end) range.value = [saved.start, saved.end]
  } catch {
    localStorage.removeItem(FILTERS_KEY)
  } finally {
    // watch 默认 flush: 'pre'，先于 nextTick 回调执行，复位前标志仍有效（同 openEdit 模式）
    nextTick(() => { restoringFilters = false })
  }
}

function resetFilters() {
  range.value = null
  Object.assign(query, { page: 1, type: null, accountId: null, categoryId: null, keyword: '' })
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
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.amt-expense { color: var(--bw-expense); font-weight: 600; font-variant-numeric: tabular-nums; }
.amt-income { color: var(--bw-income); font-weight: 600; font-variant-numeric: tabular-nums; }
</style>
