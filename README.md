# BalanceWise 个人账务管理与统计分析系统

基于 Spring Boot 3 + Vue 3 的多用户个人账务管理系统（毕业论文项目）。

## 技术栈

后端：Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、MySQL 8、JWT
前端：Vue 3.5、Vite 5.4、Element Plus 2.9、ECharts 5.6、Pinia 2.2

## 本地运行

1. 建库建表：执行 `sql/init-mysql.sql`（建库 `balancewisedb` + 建表 + 可选演示数据，
   不需要演示数据时删除文件末尾「演示数据」段即可）。
   `backend/src/main/resources/schema.sql` 为 H2 单元测试初始化专用，MySQL 环境无需手工执行。
2. 修改 `backend/src/main/resources/application.yml` 中的数据库账号密码。
3. 启动后端：`cd backend && mvn spring-boot:run`（端口 8080）
4. 启动前端：`cd frontend && npm install && npm run dev`（端口 5173）
5. 浏览器打开 http://localhost:5173 ，注册账号即可使用。

## 运行测试

`cd backend && mvn test`（使用 H2 内存库，无需 MySQL）

## 当前功能
- 用户：注册（自动初始化 39 个预置分类 + 现金账户）/ 登录（JWT）
- 账户：新建（资产/负债）、编辑、归档、删除（有交易拦截）、净资产汇总
- 分类：两级树、预置/自定义、改名、三类删除拦截（预置/有子类/被引用）
- 记账：支出/收入/转账（余额联动，负债余额为负）、流水筛选分页、编辑（冲销+入账）、删除（软删+冲销）、CSV 导出
- 导入：支付宝/微信 CSV 解析清洗（状态过滤/金额清洗）、三层分类流水线（个人映射记忆 → 关键词规则 → AI 智能分类，OpenAI 兼容接口默认硅基流动、未配 Key 自动降级）、账户四级匹配（精确/记忆/模糊/默认）、精确去重 + 疑似重复提示（金额+类型+时间窗）、预览勾选（修正可"记住"沉淀为个人映射）、映射记忆管理、批次入账与幂等撤销
- 批次：列表/明细查询，撤销后余额还原、可重新导入
- 统计：首页仪表盘（净资产/本月收支/近 6 月趋势/支出构成/最近流水）与分析页（汇总卡片、收支趋势、净资产趋势、分类构成下钻、账户总览、时间/账户/类型多维筛选）
- 余额校正（对账）：录入账户实际余额，差额自动生成「余额校正」流水（收入/支出校正，来源标记 reconcile，可追溯可冲销）
- 用户中心：修改昵称、修改密码（旧密码校验，改密后需重新登录）

## 项目文档

需求与设计规格：`docs/superpowers/specs/2026-10-21-balancewise-design.md`
实施计划：`docs/superpowers/plans/2026-10-22-phase1-foundation.md`
数据库初始化：`sql/init-mysql.sql`（MySQL 全量；H2 测试专用最小结构见 `backend/src/main/resources/schema.sql`）
界面原型：`prototype/`（纯静态可点击原型）
