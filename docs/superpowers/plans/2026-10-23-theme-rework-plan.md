# 温暖手账风主题重设计 · 实施计划

- Spec: `docs/superpowers/specs/2026-10-23-warm-theme-redesign-design.md`
- 范围：仅前端 `frontend/`，零后端改动

## 任务分解（按依赖顺序）

1. **全局主题系统**
   - 新建 `src/styles/theme.css`：4 套主题 CSS 变量 + Element Plus 变量覆盖 + 全局元素样式（body 底、卡片圆角阴影、滚动条）
   - `main.js`：挂载前从 localStorage 读取 `bw-theme` 写入 `documentElement.dataset.theme`；引入 theme.css
   - 新建 `src/stores/theme.js`（Pinia）：主题清单（id/名称/预览色）、`apply(id)`、`init()`，持久化 localStorage
   - 新建 `src/utils/emoji.js`：分类名 → emoji 映射 + 回退
2. **MainLayout**：浅色顶栏（logo、胶囊菜单、主题切换下拉、用户菜单）替换深色侧边栏；全局 FAB「✏️ 记一笔」→ `/record`
3. **DashboardView**：净资产渐变问候大卡 + 收支小卡 + 手账风最近流水列表（emoji）+ 暖色图表
4. **RecordView**：大号类型胶囊分段 + 大保存按钮（逻辑不动）
5. **LoginView / RegisterView**：奶油底居中暖卡
6. **其余页面轻扫**：Transactions / Stats / Account / Category / Import / UserCenter 适配新顶栏与全局样式，仅必要微调
7. **验证**：`npm run build` 通过；dev server 人工检查关键页面；切换主题即时生效且刷新保持

## 风险与对策

- Element Plus 组件暗色细节：通过覆盖 `--el-*` 变量解决；暗夜主题只覆盖常用控件，容忍少量不完美
- 回归风险：门面页只动模板样式，不改 script 中的数据流与 API 调用；每页改完 `npm run build` 快速验证
- 图表颜色：ECharts 写死色值，逐页替换为变量取值
