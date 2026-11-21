<template>
  <div class="bw-page">
    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col :span="8">
        <el-card><div class="stat"><span class="label">净资产</span>
          <span class="value">¥{{ money(summary.netWorth) }}</span></div></el-card>
      </el-col>
      <el-col :span="8">
        <el-card><div class="stat"><span class="label">资产合计</span>
          <span class="value income">¥{{ money(summary.assetTotal) }}</span></div></el-card>
      </el-col>
      <el-col :span="8">
        <el-card><div class="stat"><span class="label">负债合计（欠款）</span>
          <span class="value expense">¥{{ money(summary.liabilityTotal) }}</span></div></el-card>
      </el-col>
    </el-row>

    <el-card style="margin-bottom: 16px">
      <template #header>
        <div class="card-head">
          <span>资产账户</span>
          <el-button type="primary" size="small" @click="openDialog()">＋ 新建账户</el-button>
        </div>
      </template>
      <AccountRow v-for="a in assets" :key="a.id" :acc="a"
                  @reconcile="openReconcile" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
      <el-empty v-if="!assets.length" description="暂无资产账户" :image-size="60" />
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>负债账户</span>
          <el-button type="primary" size="small" @click="openDialog(null, 'liability')">＋ 新建账户</el-button>
        </div>
      </template>
      <AccountRow v-for="a in liabilities" :key="a.id" :acc="a"
                  @reconcile="openReconcile" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
      <el-empty v-if="!liabilities.length" description="暂无负债账户" :image-size="60" />
    </el-card>

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑账户' : '新建账户'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="dialog.name" maxlength="32" placeholder="如：招商储蓄卡" />
        </el-form-item>
        <el-form-item label="类别">
          <el-radio-group v-model="dialog.type" :disabled="!!dialog.id">
            <el-radio value="asset">资产</el-radio>
            <el-radio value="liability">负债</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="子类型">
          <el-select v-model="dialog.subType" style="width: 100%">
            <el-option v-for="(label, key) in subTypeOptions" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!dialog.id" label="初始余额">
          <el-input-number v-model="dialog.initialBalance" :min="0" :precision="2" style="width: 100%" />
          <div class="hint">负债账户请填写当前欠款金额</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialog.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="recon.visible" :title="`对账——${recon.name}`" width="420px">
      <el-form label-width="96px">
        <el-form-item label="账面余额">
          <span class="recon-balance">¥{{ money(recon.bookBalance) }}</span>
          <span v-if="recon.isLiability" class="hint" style="margin-left: 8px">（欠款，按正数填写）</span>
        </el-form-item>
        <el-form-item label="实际余额">
          <el-input-number v-model="recon.actual" :precision="2" :step="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="差额">
          <span :class="reconDiff > 0 ? 'income' : reconDiff < 0 ? 'expense' : 'muted'">
            {{ reconDiff > 0 ? '+' : '' }}{{ money(reconDiff) }}
          </span>
          <span class="hint" style="margin-left: 8px">
            {{ reconDiff > 0 ? '将增加余额' : reconDiff < 0 ? '将减少余额' : '无需校正' }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recon.visible = false">取消</el-button>
        <el-button type="primary" :loading="recon.saving" :disabled="reconDiff === 0" @click="submitReconcile">
          生成校正流水
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive } from 'vue'
import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'
import request from '../api/request'

const SUB_TYPES = {
  asset: { cash: '现金', debit_card: '储蓄卡', wechat: '微信零钱', alipay: '支付宝余额', yuebao: '余额宝', custom: '自定义' },
  liability: { credit_card: '信用卡', huabei: '花呗', custom: '自定义' },
}

const summary = reactive({ accounts: [], netWorth: 0, assetTotal: 0, liabilityTotal: 0 })
const assets = computed(() => summary.accounts.filter(a => a.type === 'asset'))
const liabilities = computed(() => summary.accounts.filter(a => a.type === 'liability'))
const subTypeOptions = computed(() => SUB_TYPES[dialog.type] || {})

const dialog = reactive({
  visible: false, saving: false, id: null,
  name: '', type: 'asset', subType: 'cash', initialBalance: 0, remark: '',
})

function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  const res = await request.get('/accounts')
  Object.assign(summary, res.data)
}

function openDialog(acc, presetType) {
  if (acc) {
    Object.assign(dialog, {
      id: acc.id, name: acc.name, type: acc.type,
      subType: acc.subType, initialBalance: 0, remark: acc.remark || '',
    })
  } else {
    Object.assign(dialog, {
      id: null, name: '', type: presetType || 'asset',
      subType: presetType === 'liability' ? 'credit_card' : 'cash', initialBalance: 0, remark: '',
    })
  }
  dialog.visible = true
}

async function save() {
  dialog.saving = true
  try {
    const body = {
      name: dialog.name, type: dialog.type, subType: dialog.subType,
      initialBalance: dialog.initialBalance, remark: dialog.remark,
    }
    if (dialog.id) await request.put(`/accounts/${dialog.id}`, body)
    else await request.post('/accounts', body)
    ElMessage.success('已保存')
    dialog.visible = false
    await load()
  } finally {
    dialog.saving = false
  }
}

async function toggleArchive(acc) {
  await request.put(`/accounts/${acc.id}/archive`, { isArchived: !acc.isArchived })
  ElMessage.success(acc.isArchived ? '已恢复' : '已归档')
  await load()
}

async function remove(acc) {
  try {
    await ElMessageBox.confirm(
      acc.isArchived ? `确定删除账户「${acc.name}」？` : `确定删除账户「${acc.name}」？（有交易的账户无法删除，将自动拦截）`,
      '删除确认', { type: 'warning' }
    )
  } catch { return }
  await request.delete(`/accounts/${acc.id}`)
  ElMessage.success('已删除')
  await load()
}

const recon = reactive({
  visible: false, saving: false, id: null, name: '',
  isLiability: false, bookBalance: 0, actual: 0,
})
// 展示口径差额：录入值 − 账面余额（正=将增加余额/减少欠款）；提交后以后端返回为准
const reconDiff = computed(() => Number(((recon.actual || 0) - recon.bookBalance).toFixed(2)))

function openReconcile(acc) {
  if (acc.isArchived) {
    ElMessage.warning('账户已归档，请先恢复后再对账')
    return
  }
  Object.assign(recon, {
    visible: true, saving: false, id: acc.id, name: acc.name,
    isLiability: acc.type === 'liability',
    bookBalance: acc.type === 'liability' ? -Number(acc.balance) : Number(acc.balance),
    actual: acc.type === 'liability' ? -Number(acc.balance) : Number(acc.balance),
  })
}

async function submitReconcile() {
  recon.saving = true
  try {
    const res = await request.post(`/accounts/${recon.id}/reconcile`, { actualBalance: recon.actual })
    const typeLabel = res.data.correctionType === 'income' ? '收入' : '支出'
    ElMessage.success(`已生成${typeLabel}校正流水，差额 ¥${money(Math.abs(Number(res.data.diff)))}`)
    recon.visible = false
    await load()
  } finally {
    recon.saving = false
  }
}

// 行内子组件：名称 + 子类型 + 余额 + 操作按钮（emits 显式声明，消除属性透传警告）
const AccountRow = (props, { emit }) => {
  const acc = props.acc
  const label = (SUB_TYPES[acc.type] || {})[acc.subType] || acc.subType
  const display = acc.type === 'liability' ? -Number(acc.balance) : Number(acc.balance)
  return h('div', { class: 'acc-row' }, [
    h('div', null, [
      h('span', { class: 'acc-name' }, acc.name),
      h(ElTag, { size: 'small', style: 'margin-left:8px' }, () => label),
      acc.isArchived ? h(ElTag, { size: 'small', type: 'info', style: 'margin-left:6px' }, () => '已归档') : null,
    ]),
    h('div', { class: 'acc-ops' }, [
      h('span', { class: ['acc-balance', acc.type === 'liability' ? 'expense' : ''] },
        `¥${money(display)}`),
      h(ElButton, { size: 'small', onClick: () => emit('reconcile', acc) }, () => '对账'),
      h(ElButton, { size: 'small', onClick: () => emit('edit', acc) }, () => '编辑'),
      h(ElButton, { size: 'small', onClick: () => emit('archive', acc) }, () => (acc.isArchived ? '恢复' : '归档')),
      h(ElButton, { size: 'small', type: 'danger', plain: true, onClick: () => emit('remove', acc) }, () => '删除'),
    ]),
  ])
}
AccountRow.props = { acc: { type: Object, required: true } }
AccountRow.emits = ['reconcile', 'edit', 'archive', 'remove']

onMounted(load)
</script>

<style scoped>
.stat { display: flex; flex-direction: column; gap: 6px; }
.stat .label { color: var(--bw-text-secondary); font-size: 13px; }
.stat .value { font-size: 24px; font-weight: 700; }
.stat .income { color: var(--bw-income); }
.stat .expense { color: var(--bw-expense); }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: var(--bw-text-secondary); line-height: 1.4; }
:deep(.acc-row) {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 4px; border-bottom: 1px dashed var(--bw-border);
}
:deep(.acc-row:last-child) { border-bottom: none; }
:deep(.acc-name) { font-weight: 600; }
:deep(.acc-ops) { display: flex; align-items: center; gap: 10px; }
:deep(.acc-balance) { font-variant-numeric: tabular-nums; font-weight: 600; }
:deep(.acc-balance.expense) { color: var(--bw-expense); }
.recon-balance { font-weight: 600; font-variant-numeric: tabular-nums; }
.income { color: var(--bw-income); }
.muted { color: var(--bw-text-secondary); }
</style>
