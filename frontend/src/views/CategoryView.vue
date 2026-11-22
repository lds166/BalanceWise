<template>
  <div class="bw-page">
    <div class="bw-page-title">分类管理</div>
  <el-row :gutter="16">
    <el-col :span="12">
      <el-card>
        <template #header>
          <div class="card-head">
            <span>支出分类</span>
            <el-button type="primary" size="small" @click="openCreate('expense', null)">＋ 一级分类</el-button>
          </div>
        </template>
        <div v-for="g in expenseTree" :key="g.id" class="cat-group">
          <div class="cat-parent">
            <span class="cat-name"><span class="cat-emoji">{{ categoryEmoji(g.name, 'expense') }}</span>{{ g.name }} <el-tag v-if="g.isSystem === 1" size="small" type="info" style="margin-left:6px">预置</el-tag></span>
            <span>
              <el-button size="small" text @click="openCreate('expense', g)">＋子类</el-button>
              <el-button size="small" text @click="openRename(g)">改名</el-button>
              <el-button size="small" text type="danger" @click="remove(g)">删除</el-button>
            </span>
          </div>
          <div class="cat-children">
            <span v-for="c in g.children" :key="c.id" class="cat-child">
              <span class="cat-emoji">{{ categoryEmoji(c.name, 'expense') }}</span>{{ c.name }}
              <span class="child-ops">
                <el-button size="small" text @click="openRename(c)">改名</el-button>
                <el-button size="small" text type="danger" @click="remove(c)">删除</el-button>
              </span>
            </span>
            <span v-if="!g.children.length" class="muted">（无子分类）</span>
          </div>
        </div>
      </el-card>
    </el-col>
    <el-col :span="12">
      <el-card>
        <template #header>
          <div class="card-head">
            <span>收入分类</span>
            <el-button type="primary" size="small" @click="openCreate('income', null)">＋ 一级分类</el-button>
          </div>
        </template>
        <div v-for="g in incomeTree" :key="g.id" class="cat-group">
          <div class="cat-parent">
            <span class="cat-name"><span class="cat-emoji">{{ categoryEmoji(g.name, 'income') }}</span>{{ g.name }} <el-tag v-if="g.isSystem === 1" size="small" type="info" style="margin-left:6px">预置</el-tag></span>
            <span>
              <el-button size="small" text @click="openCreate('income', g)">＋子类</el-button>
              <el-button size="small" text @click="openRename(g)">改名</el-button>
              <el-button size="small" text type="danger" @click="remove(g)">删除</el-button>
            </span>
          </div>
          <div class="cat-children">
            <span v-for="c in g.children" :key="c.id" class="cat-child">
              <span class="cat-emoji">{{ categoryEmoji(c.name, 'income') }}</span>{{ c.name }}
              <span class="child-ops">
                <el-button size="small" text @click="openRename(c)">改名</el-button>
                <el-button size="small" text type="danger" @click="remove(c)">删除</el-button>
              </span>
            </span>
            <span v-if="!g.children.length" class="muted">（无子分类）</span>
          </div>
        </div>
      </el-card>
    </el-col>
  </el-row>
  </div>

  <el-dialog v-model="dlg.visible" :title="dlg.title" width="400px">
    <el-input v-model="dlg.name" maxlength="32" placeholder="分类名称" />
    <template #footer>
      <el-button @click="dlg.visible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { categoryEmoji } from '../utils/emoji'

const expenseTree = ref([])
const incomeTree = ref([])
const dlg = reactive({ visible: false, title: '', mode: 'create', kind: 'expense', id: null, parentId: null, name: '' })

async function load() {
  const [e, i] = await Promise.all([
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  expenseTree.value = e.data
  incomeTree.value = i.data
}

function openCreate(kind, parent) {
  Object.assign(dlg, {
    visible: true, mode: 'create', kind, id: null,
    parentId: parent ? parent.id : null,
    name: '',
    title: parent ? `在「${parent.name}」下新增子分类` : `新增${kind === 'expense' ? '支出' : '收入'}一级分类`,
  })
}

function openRename(cat) {
  Object.assign(dlg, {
    visible: true, mode: 'rename', kind: cat.kind, id: cat.id,
    parentId: null, name: cat.name, title: '修改分类名称',
  })
}

async function save() {
  if (!dlg.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }
  if (dlg.mode === 'create') {
    await request.post('/categories', { name: dlg.name.trim(), kind: dlg.kind, parentId: dlg.parentId })
  } else {
    await request.put(`/categories/${dlg.id}`, { name: dlg.name.trim() })
  }
  ElMessage.success('已保存')
  dlg.visible = false
  await load()
}

async function remove(cat) {
  try {
    await ElMessageBox.confirm(`确定删除分类「${cat.name}」？（预置分类或被交易使用的分类将被拦截）`, '删除确认', { type: 'warning' })
  } catch { return }
  await request.delete(`/categories/${cat.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.cat-group { margin-bottom: 10px; }
.cat-parent {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 10px; background: var(--bw-bg); border-radius: 10px; font-weight: 600;
}
.cat-name { display: inline-flex; align-items: center; gap: 6px; }
.cat-emoji { font-size: 15px; }
.cat-children { padding: 6px 0 0 16px; display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: center; }
.cat-child { color: var(--bw-text-secondary); font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
/* 子分类操作 hover 才出现：整页 50+ 个红字"改名/删除"是主要视觉噪音源 */
.child-ops { display: inline-flex; opacity: 0; transition: opacity 0.15s ease; }
.cat-child:hover .child-ops { opacity: 1; }
.cat-child:hover { color: var(--bw-text); }
.muted { color: var(--bw-text-secondary); font-size: 12px; }
</style>
