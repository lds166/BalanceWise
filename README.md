# BalanceWise 个人账务管理与统计分析系统

基于 Spring Boot 3 + Vue 3 的多用户个人账务管理系统（毕业论文项目）。

## 技术栈

后端：Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、MySQL 8、JWT
前端：Vue 3.5、Vite 5.4、Element Plus 2.9、ECharts 5.6、Pinia 2.2

## 本地运行

1. 创建数据库：`CREATE DATABASE balancewise DEFAULT CHARSET utf8mb4;`
   并执行 `backend/src/main/resources/schema.sql` 建表。
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

## 项目文档

需求与设计规格：`docs/superpowers/specs/2026-10-21-balancewise-design.md`
实施计划：`docs/superpowers/plans/2026-10-22-phase1-foundation.md`
界面原型：`prototype/`（纯静态可点击原型）
