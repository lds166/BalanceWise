<template>
  <div>
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
      <AccountRow v-for="a in assets" :key="a.id" :acc="a" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
      <el-empty v-if="!assets.length" description="暂无资产账户" :image-size="60" />
    </el-card>

    <el-card>
      <template #header><span>负债账户</span></template>
      <AccountRow v-for="a in liabilities" :key="a.id" :acc="a" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
      <el-empty v-if="!liabilities.length" description="暂无负债账户" :image-size="60" />
    </el-card>

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑账户' : '新建账户'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="dialog.name" maxlength="32" placeholder="如：招商储蓄卡" />
        </el-form-item>
        <el-form-item label="类别">
          <el-radio-group v-model="dialog.type">
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

function openDialog(acc) {
  if (acc) {
    Object.assign(dialog, {
      id: acc.id, name: acc.name, type: acc.type,
      subType: acc.subType, initialBalance: 0, remark: acc.remark || '',
    })
  } else {
    Object.assign(dialog, {
      id: null, name: '', type: 'asset', subType: 'cash', initialBalance: 0, remark: '',
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
      acc.isArchived ? `确定删除账户「${acc.name}」？` : `账户「${acc.name}」删除后不可恢复（有交易时会被拦截），确定？`,
      '删除确认', { type: 'warning' }
    )
  } catch { return }
  await request.delete(`/accounts/${acc.id}`)
  ElMessage.success('已删除')
  await load()
}

// 行内子组件：名称 + 子类型 + 余额 + 操作按钮
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
      h(ElButton, { size: 'small', onClick: () => emit('edit', acc) }, () => '编辑'),
      h(ElButton, { size: 'small', onClick: () => emit('archive', acc) }, () => (acc.isArchived ? '恢复' : '归档')),
      h(ElButton, { size: 'small', type: 'danger', plain: true, onClick: () => emit('remove', acc) }, () => '删除'),
    ]),
  ])
}
AccountRow.props = { acc: { type: Object, required: true } }

onMounted(load)
</script>

<style scoped>
.stat { display: flex; flex-direction: column; gap: 6px; }
.stat .label { color: #8a90a3; font-size: 13px; }
.stat .value { font-size: 24px; font-weight: 700; }
.stat .income { color: #3aa97a; }
.stat .expense { color: #e8604c; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: #8a90a3; line-height: 1.4; }
:deep(.acc-row) {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 4px; border-bottom: 1px solid #f0f1f5;
}
:deep(.acc-row:last-child) { border-bottom: none; }
:deep(.acc-name) { font-weight: 600; }
:deep(.acc-ops) { display: flex; align-items: center; gap: 10px; }
:deep(.acc-balance) { font-variant-numeric: tabular-nums; font-weight: 600; }
:deep(.acc-balance.expense) { color: #e8604c; }
</style>
