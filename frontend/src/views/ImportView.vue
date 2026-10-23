<template>
  <div class="bw-page">
    <el-tabs v-model="tab">
      <el-tab-pane label="导入账单" name="upload">
        <el-card style="max-width: 720px; margin-bottom: 16px">
          <el-form label-width="90px" inline @submit.prevent>
            <el-form-item label="平台">
              <el-radio-group v-model="form.platform">
                <el-radio-button value="alipay">支付宝</el-radio-button>
                <el-radio-button value="wechat">微信</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="默认账户">
              <el-select v-model="form.defaultAccountId" filterable clearable placeholder="匹配不到时使用的账户" style="width: 180px">
                <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="文件">
              <el-upload ref="uploadRef" :auto-upload="false" :limit="1" accept=".csv"
                         :on-exceed="onExceed" :on-change="onFileChange" :on-remove="onFileRemove">
                <el-button>选择 CSV 文件</el-button>
                <template #tip><span class="muted" style="margin-left: 10px">≤ 10 MB，支付宝/微信官方导出格式</span></template>
              </el-upload>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="parsing" @click="parse">解析预览</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-if="preview.rows.length || preview.dropped.length">
          <template #header>
            <div class="card-head">
              <span>预览（{{ preview.rows.length }} 条，丢弃 {{ preview.dropped.length }} 条）</span>
              <el-button type="primary" :disabled="!checkedCount" :loading="confirming"
                         @click="confirm">确认入账（{{ checkedCount }} 条）</el-button>
            </div>
          </template>
          <div v-if="preview.dropped.length" class="muted" style="margin-bottom: 8px">
            丢弃：{{ preview.dropped.join('；') }}
          </div>
          <el-table :data="preview.rows" size="small" max-height="480">
            <el-table-column label="时间" prop="occurredAt" width="160" />
            <el-table-column label="类型" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'expense' ? 'danger' : row.type === 'income' ? 'success' : 'info'">
                  {{ { expense: '支出', income: '收入', transfer: '转账' }[row.type] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="对方" prop="counterparty" width="120" show-overflow-tooltip />
            <el-table-column label="商品" prop="item" width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.item || '—' }}</template>
            </el-table-column>
            <el-table-column label="分类" width="160">
              <template #default="{ row }">
                <el-cascader v-if="row.type !== 'transfer'" v-model="row.categoryId"
                             :options="optionsOf(row.type)" :props="cascaderProps"
                             filterable clearable placeholder="未分类" size="small" style="width: 100%" />
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="账户" min-width="220">
              <template #default="{ row }">
                <div class="acc-cell">
                  <el-select v-if="row.type !== 'income'" v-model="row.fromAccountId" filterable size="small"
                             placeholder="转出/支出账户" style="width: 100px">
                    <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                  <el-select v-if="row.type !== 'expense'" v-model="row.toAccountId" filterable size="small"
                             :placeholder="row.type === 'transfer' ? '转入账户' : '收入账户'" style="width: 100px">
                    <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="重复" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.duplicate" size="small" type="warning">重复</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="入账" width="55">
              <template #default="{ row }">
                <el-checkbox v-model="row.checked" :disabled="row.duplicate" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="批次记录" name="batches">
        <el-card>
          <el-table :data="batches" size="small">
            <el-table-column label="入账时间" prop="importedAt" width="165" />
            <el-table-column label="平台" width="80">
              <template #default="{ row }">{{ row.platform === 'alipay' ? '支付宝' : '微信' }}</template>
            </el-table-column>
            <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
            <el-table-column prop="recordCount" label="笔数" width="70" align="right" />
            <el-table-column label="合计金额" width="110" align="right">
              <template #default="{ row }">{{ money(row.totalAmount) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'confirmed' ? 'success' : 'info'">
                  {{ row.status === 'confirmed' ? '已入账' : '已撤销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button size="small" text @click="openDetail(row)">明细</el-button>
                <el-button v-if="row.status === 'confirmed'" size="small" text type="danger" @click="rollback(row)">撤销</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无导入批次" :image-size="60" /></template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detail.visible" title="批次明细" width="760px">
      <el-table :data="detail.records" size="small" max-height="420">
        <el-table-column prop="occurredAt" label="时间" width="160" />
        <el-table-column label="类型" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'expense' ? 'danger' : row.type === 'income' ? 'success' : 'info'">
              {{ { expense: '支出', income: '收入', transfer: '转账' }[row.type] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="100">
          <template #default="{ row }">{{ row.categoryName || '—' }}</template>
        </el-table-column>
        <el-table-column label="账户" min-width="140">
          <template #default="{ row }">
            {{ row.type === 'transfer' ? `${row.fromAccountName || ''} → ${row.toAccountName || ''}`
              : row.type === 'expense' ? (row.fromAccountName || '—') : (row.toAccountName || '—') }}
          </template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="counterparty" label="对方" width="120" show-overflow-tooltip />
        <el-table-column prop="item" label="商品" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const tab = ref('upload')
const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: false }
const uploadRef = ref()
const pickedFile = ref(null)

const form = reactive({ platform: 'alipay', defaultAccountId: null })
const parsing = ref(false)
const confirming = ref(false)
const preview = reactive({ fileName: '', rows: [], dropped: [] })
const batches = ref([])

const optionsOf = type => (type === 'income' ? incomeTree.value : expenseTree.value)
const checkedCount = computed(() => preview.rows.filter(r => r.checked && !r.duplicate).length)
const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function onFileChange(file) {
  pickedFile.value = file.raw
}
function onFileRemove() {
  pickedFile.value = null
}
function onExceed(files) {
  // 只保留最新选择的文件
  uploadRef.value.clearFiles()
  uploadRef.value.handleStart(files[0])
  pickedFile.value = files[0]
}

onMounted(async () => {
  const [acc, exp, inc] = await Promise.all([
    request.get('/accounts'),
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  accounts.value = acc.data.accounts.filter(a => !a.isArchived)
  expenseTree.value = exp.data
  incomeTree.value = inc.data
  await loadBatches()
})

async function parse() {
  if (!pickedFile.value) return ElMessage.warning('请先选择 CSV 文件')
  parsing.value = true
  try {
    const fd = new FormData()
    fd.append('platform', form.platform)
    fd.append('file', pickedFile.value)
    if (form.defaultAccountId) fd.append('defaultAccountId', form.defaultAccountId)
    const res = await request.post('/imports/preview', fd)
    preview.fileName = res.data.fileName
    preview.dropped = res.data.dropped
    preview.rows = res.data.rows.map(r => ({ ...r, checked: !r.duplicate }))
    if (!res.data.rows.length) ElMessage.warning('没有可导入的流水')
  } finally {
    parsing.value = false
  }
}

async function confirm() {
  const missing = preview.rows.find(r => r.checked && !r.duplicate
    && (r.type !== 'income' && !r.fromAccountId || r.type !== 'expense' && !r.toAccountId))
  if (missing) return ElMessage.warning(`第 ${missing.lineNo} 行还未选择账户`)
  confirming.value = true
  try {
    await request.post('/imports/confirm', {
      platform: form.platform,
      fileName: preview.fileName,
      rows: preview.rows.filter(r => r.checked && !r.duplicate).map(r => ({
        type: r.type, amount: r.amount, occurredAt: r.occurredAt,
        counterparty: r.counterparty, item: r.item, externalId: r.externalId,
        categoryId: r.type === 'transfer' ? null : r.categoryId,
        fromAccountId: r.type === 'income' ? null : r.fromAccountId,
        toAccountId: r.type === 'expense' ? null : r.toAccountId,
      })),
    })
    ElMessage.success('批次已入账')
    preview.rows = []
    preview.dropped = []
    uploadRef.value.clearFiles()
    pickedFile.value = null
    tab.value = 'batches'
    await loadBatches()
  } finally {
    confirming.value = false
  }
}

async function loadBatches() {
  const res = await request.get('/imports')
  batches.value = res.data
}

const detail = reactive({ visible: false, records: [] })

async function openDetail(row) {
  const res = await request.get(`/imports/${row.id}`)
  detail.records = res.data.records
  detail.visible = true
}

async function rollback(row) {
  try {
    await ElMessageBox.confirm(
      `确定撤销批次「${row.fileName}」？该批 ${row.recordCount} 条流水将冲销余额并软删除，文件可重新导入。`,
      '撤销确认', { type: 'warning' })
  } catch { return }
  await request.post(`/imports/${row.id}/rollback`)
  ElMessage.success('批次已撤销')
  await loadBatches()
}
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.acc-cell { display: flex; gap: 6px; }
.muted { color: var(--bw-text-secondary); font-size: 12px; }
</style>
