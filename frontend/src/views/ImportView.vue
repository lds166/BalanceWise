<template>
  <div class="bw-page">
    <div class="bw-page-title">账单导入</div>
    <div class="page-sub">上传支付宝/微信官方导出的 CSV 账单，自动识别分类与账户，预览确认后入账</div>
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
              <el-upload ref="uploadRef" :auto-upload="false" :limit="1" accept=".csv,.xlsx,.xls"
                         :on-exceed="onExceed" :on-change="onFileChange" :on-remove="onFileRemove">
                <el-button>选择账单文件</el-button>
                <template #tip><span class="muted" style="margin-left: 10px">≤ 10 MB；支付宝导出 CSV / 微信导出 XLSX（微信压缩包需先解压再上传）</span></template>
              </el-upload>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="parsing" @click="parse">解析预览</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-if="preview.show">
          <template #header>
            <div class="card-head">
              <span>预览（{{ preview.rows.length }} 条，丢弃 {{ preview.dropped.length }} 条）</span>
              <div class="confirm-bar">
                <el-checkbox v-model="remember" size="small">记住本次修改（下次自动套用）</el-checkbox>
                <el-button type="primary" :disabled="!checkedCount" :loading="confirming"
                           @click="confirm">确认入账（{{ checkedCount }} 条）</el-button>
              </div>
            </div>
          </template>
          <el-empty v-if="!preview.rows.length && !preview.dropped.length"
                    description="该文件中没有识别到有效流水（请确认文件与所选平台一致）" :image-size="70" />
          <div v-if="preview.dropped.length" class="muted" style="margin-bottom: 8px">
            丢弃：{{ droppedPreview }}<template v-if="preview.dropped.length > 3">；等共 {{ preview.dropped.length }} 条
              <el-button link type="primary" size="small" @click="showAllDropped = !showAllDropped">
                {{ showAllDropped ? '收起' : '展开全部' }}
              </el-button>
            </template>
            <div v-if="showAllDropped && preview.dropped.length > 3" class="dropped-all">
              <div v-for="(d, i) in preview.dropped" :key="i">{{ d }}</div>
            </div>
          </div>
          <el-alert v-if="preview.notice" :title="preview.notice" type="info" :closable="false"
                    style="margin-bottom: 8px" />
          <el-table v-if="preview.rows.length" :data="preview.rows" size="small" max-height="480"
                    :row-class-name="rowClass">
            <el-table-column label="时间" prop="occurredAt" width="160" :formatter="fmtTimeCol" />
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
            <el-table-column label="分类" width="170">
              <template #default="{ row }">
                <div v-if="row.type !== 'transfer'" class="cat-cell">
                  <el-tooltip v-if="row.matchSource === 'ai'"
                              content="AI 推荐分类，可修改；修改并勾选「记住」后会沉淀为你的个人映射"
                              placement="top">
                    <el-tag size="small" type="info" effect="plain">AI</el-tag>
                  </el-tooltip>
                  <el-cascader v-model="row.categoryId"
                               :options="optionsOf(row.type)" :props="cascaderProps"
                               filterable clearable placeholder="未分类" size="small" style="flex: 1"
                               @change="row.catEdited = true" />
                </div>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="账户" min-width="220">
              <template #default="{ row }">
                <div class="acc-cell">
                  <el-select v-if="row.type !== 'income'" v-model="row.fromAccountId" filterable size="small"
                             placeholder="转出/支出账户" style="width: 100px" @change="row.acctEdited = true">
                    <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                  <el-select v-if="row.type !== 'expense'" v-model="row.toAccountId" filterable size="small"
                             :placeholder="row.type === 'transfer' ? '转入账户' : '收入账户'" style="width: 100px"
                             @change="row.acctEdited = true">
                    <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="重复" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.duplicate" size="small" type="warning">重复</el-tag>
                <el-tooltip v-else-if="row.duplicateSuspect"
                            content="库中已有金额相同、类型相同、时间接近的记录，请人工确认是否同一笔" placement="top">
                  <el-tag size="small" type="warning" effect="plain">疑似重复</el-tag>
                </el-tooltip>
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
            <el-table-column label="入账时间" prop="importedAt" width="165" :formatter="fmtTimeCol" />
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

      <el-tab-pane label="映射记忆" name="aliases">
        <el-card>
          <div class="muted" style="margin-bottom: 8px">
            导入预览中修正分类/账户并勾选「记住」后会沉淀在这里，下次导入自动套用（优先于关键词规则）；误记的条目可在此删除。
          </div>
          <el-table :data="aliases" size="small">
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.aliasType === 'category' ? 'success' : 'primary'">
                  {{ row.aliasType === 'category' ? '分类' : '账户' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="aliasText" label="记忆内容" min-width="180" show-overflow-tooltip />
            <el-table-column prop="targetName" label="套用目标" min-width="140" show-overflow-tooltip />
            <el-table-column prop="hitCount" label="命中次数" width="90" align="right" />
            <el-table-column label="更新时间" prop="updatedAt" width="165" :formatter="fmtTimeCol" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button size="small" text type="danger" @click="removeAlias(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无映射记忆（在导入预览中修正并勾选「记住」后生成）" :image-size="60" />
            </template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detail.visible" title="批次明细" width="760px">
      <el-table :data="detail.records" size="small" max-height="420">
        <el-table-column prop="occurredAt" label="时间" width="160" :formatter="fmtTimeCol" />
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
const preview = reactive({ fileName: '', rows: [], dropped: [], notice: '', show: false })
const showAllDropped = ref(false)
const batches = ref([])
const aliases = ref([])
const remember = ref(true)

const optionsOf = type => (type === 'income' ? incomeTree.value : expenseTree.value)
const checkedCount = computed(() => preview.rows.filter(r => r.checked && !r.duplicate).length)
const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
// 时间列归一化：后端 ISO "yyyy-MM-ddTHH:mm:ss" → "yyyy-MM-dd HH:mm"（与流水页口径一致）
const fmtTimeCol = (row, col, v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '—')
// 丢弃原因只展示前 3 条，避免大文件撑爆卡片
const droppedPreview = computed(() => preview.dropped.slice(0, 3).join('；'))
// 勾选行缺账户时整行高亮，便于一次找齐
const rowClass = ({ row }) => (row.checked && !row.duplicate
  && (row.type !== 'income' && !row.fromAccountId || row.type !== 'expense' && !row.toAccountId)
  ? 'missing-row' : '')

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
  await Promise.all([loadBatches(), loadAliases()])
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
    preview.notice = res.data.notice || ''
    preview.rows = res.data.rows.map(r => ({ ...r, checked: !r.duplicate && !r.duplicateSuspect }))
    preview.show = true
    if (!res.data.rows.length) ElMessage.warning('没有可导入的流水')
  } finally {
    parsing.value = false
  }
}

async function confirm() {
  const missingRows = preview.rows.filter(r => r.checked && !r.duplicate
    && (r.type !== 'income' && !r.fromAccountId || r.type !== 'expense' && !r.toAccountId))
  if (missingRows.length) {
    return ElMessage.warning(`还有 ${missingRows.length} 行未选择账户（已在表格中标黄），请补全后再入账`)
  }
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
        payMethod: r.payMethod,
        learnCategory: remember.value && !!r.catEdited && r.type !== 'transfer',
        learnAccount: remember.value && !!r.acctEdited,
      })),
    })
    ElMessage.success('批次已入账')
    preview.rows = []
    preview.dropped = []
    preview.notice = ''
    preview.show = false
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

async function loadAliases() {
  const res = await request.get('/imports/aliases')
  aliases.value = res.data
}

async function removeAlias(row) {
  try {
    await ElMessageBox.confirm(
      `删除这条记忆「${row.aliasText} → ${row.targetName}」？删除后下次导入不再自动套用。`,
      '删除记忆', { type: 'warning' })
  } catch { return }
  await request.delete(`/imports/aliases/${row.id}`)
  ElMessage.success('记忆已删除')
  await loadAliases()
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
.confirm-bar { display: flex; align-items: center; gap: 12px; }
.cat-cell { display: flex; align-items: center; gap: 4px; width: 100%; }
.acc-cell { display: flex; gap: 6px; }
.muted { color: var(--bw-text-secondary); font-size: 12px; }
.dropped-all { max-height: 160px; overflow: auto; margin-top: 6px; padding: 6px 10px;
               background: var(--bw-bg); border-radius: 8px; line-height: 1.8; }
:deep(.missing-row) { background: var(--el-color-warning-light-9); }
</style>
