<template>
  <el-card style="max-width: 640px">
    <template #header><span>记一笔</span></template>
    <el-form label-width="90px" @submit.prevent>
      <el-form-item label="类型">
        <el-radio-group v-model="form.type">
          <el-radio-button value="expense">支出</el-radio-button>
          <el-radio-button value="income">收入</el-radio-button>
          <el-radio-button value="transfer">转账</el-radio-button>
        </el-radio-group>
      </el-form-item>
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
        <el-input-number v-model="form.amount" :min="0.01" :max="99999999" :precision="2" style="width: 100%" />
      </el-form-item>
      <el-form-item label="时间">
        <el-date-picker v-model="form.occurredAt" type="datetime" style="width: 100%" />
      </el-form-item>
      <el-form-item label="对方">
        <el-input v-model="form.counterparty" maxlength="128" placeholder="选填" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.item" maxlength="255" placeholder="选填" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'

const router = useRouter()
const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const saving = ref(false)

const empty = () => ({
  type: 'expense', fromAccountId: null, toAccountId: null,
  categoryId: null, amount: null, occurredAt: null,
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
    ElMessage.success('已记账')
    router.push('/transactions')
  } finally {
    saving.value = false
  }
}
</script>
