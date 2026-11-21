<template>
  <div class="bw-page record-page">
    <div class="bw-card form-card">
      <div class="form-head">
        <span class="form-title">✏️ 记一笔</span>
        <span class="form-hint">随手一记，账目不迷路</span>
      </div>

      <!-- 类型大胶囊 -->
      <div class="type-pills">
        <button
          v-for="t in types"
          :key="t.value"
          class="type-pill"
          :class="[t.value, { active: form.type === t.value }]"
          @click="form.type = t.value"
        >
          <span class="pill-emoji">{{ t.emoji }}</span>{{ t.label }}
        </button>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item v-if="form.type !== 'income'" :label="form.type === 'transfer' ? '转出账户' : '账户'">
          <el-select v-model="form.fromAccountId" filterable placeholder="选择账户" style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.type !== 'expense'" :label="form.type === 'transfer' ? '转入账户' : '账户'">
          <el-select v-model="form.toAccountId" filterable placeholder="选择账户" style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.type !== 'transfer'" label="分类">
          <el-cascader
            v-model="form.categoryId"
            :options="categoryOptions"
            :props="cascaderProps"
            filterable
            clearable
            placeholder="未分类"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="金额">
          <div class="amount-box" :class="form.type">
            <span class="currency">¥</span>
            <el-input-number
              v-model="form.amount"
              :min="0.01"
              :max="99999999"
              :precision="2"
              :controls="false"
              placeholder="0.00"
              class="amount-input"
            />
          </div>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="form.occurredAt" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item label="对方">
          <el-input v-model="form.counterparty" maxlength="128" placeholder="选填" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.item" maxlength="255" placeholder="选填，例如：和同事的午饭" />
        </el-form-item>
        <el-form-item>
          <el-button class="save-btn" type="primary" :loading="saving" @click="save">
            💾 保存这笔
          </el-button>
          <el-button class="reset-btn" text @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api/request'

const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const saving = ref(false)

const types = [
  { value: 'expense', label: '支出', emoji: '🛍️' },
  { value: 'income', label: '收入', emoji: '💰' },
  { value: 'transfer', label: '转账', emoji: '🔄' },
]

const empty = () => ({
  type: 'expense', fromAccountId: null, toAccountId: null,
  categoryId: null, amount: null, occurredAt: new Date(),
  counterparty: '', item: '',
})
const form = reactive(empty())

const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: false }
const activeKind = computed(() => (form.type === 'income' ? 'income' : 'expense'))
const categoryOptions = computed(() => (activeKind.value === 'income' ? incomeTree.value : expenseTree.value))

watch(activeKind, () => { form.categoryId = null })

onMounted(async () => {
  const [acc, exp, inc] = await Promise.all([
    request.get('/accounts'),
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  accounts.value = acc.data.accounts.filter(a => !a.isArchived)
  expenseTree.value = exp.data
  incomeTree.value = inc.data
})

// Date → 本地 'yyyy-MM-ddTHH:mm:ss'（避免 toJSON 转成带 Z 的 UTC 串导致时间偏移）
function fmtLocal(d) {
  if (!d) return null
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function reset() {
  Object.assign(form, empty())
}

async function save() {
  if (!form.amount || form.amount <= 0) return ElMessage.warning('请输入金额')
  if (form.type !== 'income' && !form.fromAccountId) return ElMessage.warning('请选择账户')
  if (form.type !== 'expense' && !form.toAccountId) return ElMessage.warning('请选择账户')
  saving.value = true
  try {
    await request.post('/transactions', {
      type: form.type,
      amount: form.amount,
      occurredAt: fmtLocal(form.occurredAt),
      counterparty: form.counterparty || null,
      item: form.item || null,
      categoryId: form.type === 'transfer' ? null : form.categoryId,
      fromAccountId: form.type === 'income' ? null : form.fromAccountId,
      toAccountId: form.type === 'expense' ? null : form.toAccountId,
    })
    ElMessage.success('已记账 🎉 可继续记下一笔')
    // 连续记账场景：留在本页，保留类型/账户/分类，仅清空金额与备注，时间顺延为当前
    form.amount = null
    form.counterparty = ''
    form.item = ''
    form.occurredAt = new Date()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.record-page { display: flex; justify-content: center; }
.form-card { width: 640px; max-width: 100%; }
.form-head { margin-bottom: 18px; }
.form-title { font-size: 20px; font-weight: 800; margin-right: 10px; }
.form-hint { color: var(--bw-text-secondary); font-size: 13px; }

.type-pills { display: flex; gap: 12px; margin-bottom: 22px; }
.type-pill {
  flex: 1;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 14px 0; border-radius: 16px;
  border: 2px solid var(--bw-border); background: var(--bw-bg);
  color: var(--bw-text-secondary);
  font-size: 16px; font-weight: 700; font-family: inherit; cursor: pointer;
  transition: all 0.16s ease;
}
.type-pill .pill-emoji { font-size: 19px; }
.type-pill:hover { transform: translateY(-1px); border-color: var(--bw-primary); }
.type-pill.active { color: #fff; border-color: transparent; box-shadow: 0 6px 16px rgba(0, 0, 0, 0.14); }
.type-pill.expense.active { background: var(--bw-expense); }
.type-pill.income.active { background: var(--bw-income); }
.type-pill.transfer.active { background: var(--bw-primary); }

.amount-box {
  display: flex; align-items: center; width: 100%;
  background: var(--bw-bg); border: 1.5px solid var(--bw-border);
  border-radius: var(--bw-radius); padding: 4px 16px;
  transition: border-color 0.15s ease;
}
.amount-box:focus-within { border-color: var(--bw-primary); }
.amount-box.expense:focus-within { border-color: var(--bw-expense); }
.amount-box.income:focus-within { border-color: var(--bw-income); }
.currency { font-size: 22px; font-weight: 800; margin-right: 10px; }
.amount-box.expense .currency { color: var(--bw-expense); }
.amount-box.income .currency { color: var(--bw-income); }
.amount-box:not(.expense):not(.income) .currency { color: var(--bw-primary); }
.amount-input :deep(.el-input__wrapper) { background: transparent; box-shadow: none !important; }
.amount-input :deep(.el-input__inner) { font-size: 26px; font-weight: 800; height: 44px; font-variant-numeric: tabular-nums; }

.save-btn {
  flex: 1; height: 48px; font-size: 17px; font-weight: 700;
  border-radius: 999px; letter-spacing: 2px;
}
.reset-btn { margin-left: 10px; }

@media (max-width: 700px) {
  .type-pills { gap: 8px; }
  .type-pill { font-size: 14px; padding: 12px 0; }
}
</style>
