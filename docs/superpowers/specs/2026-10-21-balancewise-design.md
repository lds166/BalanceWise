# BalanceWise 个人账务管理与统计分析系统——需求与设计规格说明书

| 项目 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-10-21 |
| 作者 | 李东升 |
| 指导教师 | 于爱华 |
| 对应论文 | 第 3 章（系统需求分析）、第 4 章（系统设计）素材 |

## 0. 文档说明

本文档是系统的唯一权威规格说明，同时承担两个用途：

1. **开发依据**：后续编码、测试、验收均以本文档为准；
2. **论文章节素材**：第 3 章（可行性分析、功能/非功能需求、用例模型）与第 4 章（架构、数据库 E-R、模块设计、业务流程）的内容组织与本文档一一对应，写作论文时可按章节裁剪改写。

任何需求或设计的变更，先改本文档，再改代码。

## 1. 概述

### 1.1 项目背景

个人财务数据分散在现金、银行卡、微信、支付宝等多个渠道，现有记账产品存在"功能冗余"与"简化过度"两极分化的问题。本系统旨在提供一个以多账户统一管理、账单导入自动记账为核心、轻量可控的个人账务管理工具。详细背景见开题报告文献综述。

### 1.2 建设目标

设计并实现一个基于 Web 的多用户个人账务管理系统：

- 统一管理资产类账户（现金、储蓄卡、微信零钱、支付宝余额、余额宝）与负债类账户（信用卡、花呗），实时计算净资产；
- 支持支付宝、微信官方导出 CSV 账单的解析、清洗、自动分类与批量入账（"上传—预览—确认—入账"流程）；
- 通过事务与余额联动机制，保证任意操作（记账、编辑、删除、导入、撤销）下账务数据的一致性与可追溯性；
- 以图表直观呈现收支趋势、分类构成与账户总览。

### 1.3 系统范围

**包含**：用户注册登录、账户管理、两级收支分类管理、手工记账（支出/收入/转账）、流水查询与编辑删除、支付宝/微信 CSV 账单导入与批次撤销、多维统计报表、首页仪表盘。

**不包含**（YAGNI）：预算管理、多人共享账本、多币种、图片附件、自动同步（依赖平台接口，受限于合规性）。

### 1.4 术语约定

| 术语 | 含义 |
|---|---|
| 流水 / 交易记录 | transaction_record 表中的一条收支或转账记录 |
| 入账 | 将流水写入系统并联动更新账户余额 |
| 冲销 | 按流水差额反向调整账户余额，使该笔流水的余额影响归零 |
| 批次 | 一次账单导入产生的 import_batch 及其关联流水集合 |
| 净资产 | 全部账户余额之和（负债账户余额为负，见 5.4） |

### 1.5 可行性分析

- **技术可行性**：Spring Boot + Vue + MySQL 均为成熟开源技术，资料丰富；CSV 解析、事务处理、图表可视化均有成熟方案。开发机只需 JDK 17、Node.js 18+、MySQL 8。
- **经济可行性**：全部技术栈零许可成本，仅需一台普通 PC 即可完成开发与运行。
- **操作可行性**：界面采用 Element Plus 组件，交互模式与主流记账应用一致，用户无需培训即可上手。

## 2. 总体设计

### 2.1 架构

前后端分离的单体应用。前端 Vue 3 单页应用通过 REST + JSON 与后端通信；后端 Spring Boot 经典四层架构；开发期由 Vite 代理转发至后端避免跨域问题。

```
浏览器 (Vue 3 SPA, Element Plus + ECharts)
        │  REST / JSON, JWT Bearer Token
        ▼
Spring Boot 应用
  ├─ controller  接口层：参数校验、出参组装
  ├─ service     业务层：业务规则、事务边界 (@Transactional)
  ├─ mapper      数据访问层：MyBatis-Plus
  └─ common/config：统一返回、全局异常、JWT 拦截、CORS、MyBatis-Plus 配置
        │
        ▼
      MySQL 8
```

### 2.2 技术选型

| 层次 | 技术 | 版本 |
|---|---|---|
| 后端框架 | Spring Boot | 3.x |
| 语言 | Java | 17 |
| 持久层 | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.x |
| 鉴权 | JWT (jjwt) + Spring Boot 拦截器 | — |
| 密码散列 | BCrypt | — |
| 前端框架 | Vue + Vite + Pinia + Vue Router | 3.x |
| UI 组件 | Element Plus | 2.x |
| 图表 | ECharts | 5.x |
| HTTP 客户端 | axios | 1.x |
| 后端测试 | JUnit 5 + Spring Boot Test + MockMvc | — |

### 2.3 项目结构

```
BalanceWise/
├── backend/                  # Spring Boot 应用（Maven）
│   └── src/main/java/.../    # controller / service / mapper / entity / dto / vo / common / config
├── frontend/                 # Vue 3 + Vite 工程
├── docs/                     # 本文档及其他项目文档
└── README.md                 # 运行说明
```

## 3. 功能需求

系统有且仅有一类参与者：**注册用户（普通用户）**。各模块功能点如下，每个功能点对应论文用例模型中的一个用例。

### 3.1 用户模块

| 编号 | 功能点 | 说明 |
|---|---|---|
| UC-1.1 | 注册 | 用户名唯一校验；密码 ≥ 6 位且二次确认；昵称选填；注册成功跳转登录页 |
| UC-1.2 | 登录 | 用户名 + 密码，BCrypt 校验；失败统一提示"用户名或密码错误"；成功返回 JWT 与用户信息 |
| UC-1.3 | 鉴权 | 除注册/登录外所有接口校验 token；无效或过期返回 401，前端跳登录页 |
| UC-1.4 | 退出登录 | 清除前端 token，回到登录页 |
| UC-1.5 | 修改密码（可选） | 旧密码校验通过后设置新密码 |

注册成功时，系统为用户初始化预置分类（见 3.3）和可选的默认账户（现金账户，余额 0）。

### 3.2 账户模块

| 编号 | 功能点 | 说明 |
|---|---|---|
| UC-2.1 | 新建账户 | 名称、资产/负债类别、子类型（cash / debit_card / wechat / alipay / yuebao / credit_card / huabei / custom）、初始余额、备注 |
| UC-2.2 | 编辑账户 | 修改名称、子类型、备注；修改初始余额时差额自动计入当前余额（余额校正） |
| UC-2.3 | 删除账户 | 仅无交易的账户可删除；有交易的账户禁止删除，保证流水可追溯 |
| UC-2.4 | 归档 / 恢复 | 归档后不出现在记账下拉框，历史流水与统计仍可见 |
| UC-2.5 | 净资产总览 | 净资产 = 全部账户余额求和（负债为负）；展示各账户余额列表 |
| UC-2.6 | 账户排序（可选） | 自定义展示顺序 |

### 3.3 分类模块

预置分类（注册时复制到用户名下，`is_system = 1`，可改名、不可删除）：

- **支出**：餐饮（三餐、外卖、聚餐、零食饮料）；交通（公交地铁、打车、火车机票、加油停车）；购物（服饰鞋包、数码家电、日用百货、美妆护肤）；居住（房租、水电燃气、物业宽带）；娱乐（电影游戏、旅游度假、运动健身、会员充值）；医疗（药品、门诊住院）；教育（书籍资料、课程培训）；人情往来（红包礼金、请客送礼）；其他支出
- **收入**：工资薪资、兼职副业、理财收益、红包礼金、其他收入

| 编号 | 功能点 | 说明 |
|---|---|---|
| UC-3.1 | 分类树查看 | 按支出/收入分组的两级树形展示 |
| UC-3.2 | 新增分类 | 可新增一级或二级分类；同级下名称查重 |
| UC-3.3 | 编辑分类 | 改名；预置与自定义分类均可改名 |
| UC-3.4 | 删除分类 | 仅自定义分类可删除；被交易引用的分类禁止删除，提示用户先调整相关流水 |

未选分类的流水 `category_id` 为 NULL，统计时归入"未分类"组单列。

### 3.4 记账模块

| 编号 | 功能点 | 说明 |
|---|---|---|
| UC-4.1 | 记支出 / 记收入 | 选账户、金额（> 0）、分类、日期时间（默认当前）、备注 |
| UC-4.2 | 记转账 | 转出账户、转入账户（两账户不可相同）、金额、备注 |
| UC-4.3 | 流水列表 | 分页展示；筛选：时间范围、类型、账户、分类、备注关键字；默认时间倒序 |
| UC-4.4 | 编辑流水 | 全字段可改；保存时事务内"旧记录冲销 + 新记录入账"（见 7.2） |
| UC-4.5 | 删除流水 | 确认弹窗；事务内冲销余额并软删除（`is_deleted = 1`），流水保留可追溯 |

### 3.5 账单导入模块

| 编号 | 功能点 | 说明 |
|---|---|---|
| UC-5.1 | 上传账单 | 选择平台（支付宝 / 微信）+ CSV 文件（≤ 10 MB） |
| UC-5.2 | 解析清洗 | 按平台格式解析 CSV；剔除表头说明行；金额去货币符号与千分位；仅保留交易成功记录，其余丢弃并记录原因 |
| UC-5.3 | 类型识别 | 收/支 → 收入/支出；余额宝转入转出、零钱充值提现等 → 转账 |
| UC-5.4 | 分类自动映射 | 内置"平台交易分类 → 本系统分类"映射规则；无匹配归入未分类 |
| UC-5.5 | 入账账户匹配 | 按支付方式自动匹配用户账户（如"余额宝"→余额宝账户）；匹配不到的留空由用户指定 |
| UC-5.6 | 重复识别 | 按平台交易单号比对库中未删除记录与当前批次内记录；重复行标记并默认不勾选 |
| UC-5.7 | 预览确认 | 逐行展示（日期、类型、金额、对方、分类下拉、账户下拉、重复标记、勾选框），不落库；用户可修改分类/账户后勾选 |
| UC-5.8 | 批次入账 | 仅勾选行入账；单事务写入批次与流水并逐条更新余额 |
| UC-5.9 | 批次列表 | 展示批次（时间、平台、文件名、笔数、合计金额、状态） |
| UC-5.10 | 查看批次明细 | 查看某批次关联的全部流水 |
| UC-5.11 | 批次撤销 | 整批事务回滚：流水软删除 + 余额反向冲销 + 批次置为已撤销（见 7.5） |

### 3.6 统计报表模块

| 编号 | 功能点 | 说明 |
|---|---|---|
| UC-6.1 | 汇总卡片 | 时间范围内收入合计、支出合计、结余 |
| UC-6.2 | 收支趋势 | 折线图（收入线、支出线、结余）；按月（近 6 / 12 个月）或按日（自定义区间） |
| UC-6.3 | 分类构成 | 饼图/环形图；支出或收入可选；按一级分类聚合；时间筛选；点击扇区下钻二级分类（可选） |
| UC-6.4 | 账户总览 | 各账户余额对比条形图 + 资产/负债占比 |
| UC-6.5 | 多维筛选 | 时间（本月/上月/今年/自定义）、账户、类型 |

### 3.7 首页仪表盘

- 净资产卡片、本月收支卡片、近 6 个月收支趋势迷你图、本月支出分类饼图、最近 10 条流水。

## 4. 非功能需求

| 类别 | 要求 |
|---|---|
| 安全性 | 密码 BCrypt 散列存储；除注册/登录外全部接口 JWT 鉴权；SQL 全部经 MyBatis-Plus 参数化，杜绝注入；越权校验——所有数据操作校验资源归属当前用户 |
| 一致性 | 余额联动、冲销重算等全部账务写操作在数据库事务内完成，满足 ACID |
| 性能 | 列表全部分页；流水表建立 (user_id, occurred_at) 等常用索引；单用户数据量 10 万级流水时统计接口响应 < 1 s |
| 可靠性 | 事务失败整体回滚；导入、撤销具备幂等性（批次状态机约束） |
| 兼容性 | 支持 Chrome / Edge 等现代浏览器；后端运行于 Windows / Linux |
| 可维护性 | 分层架构、统一返回格式与全局异常处理；关键业务规则（分类映射、账户匹配）集中维护 |

## 5. 数据库设计

### 5.1 表清单

| 表名 | 说明 |
|---|---|
| user | 用户表 |
| account | 账户表（冗余当前余额） |
| category | 分类表（自关联两级树） |
| transaction_record | 交易记录表（流水） |
| import_batch | 导入批次表 |

所有表逻辑外键关联 user_id，数据按用户严格隔离；物理外键仅用于表结构表达，实际约束在应用层保证（便于测试数据构造与维护）。

### 5.2 表结构

**user**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| username | VARCHAR(32) NOT NULL UNIQUE | 用户名 |
| password_hash | VARCHAR(100) NOT NULL | BCrypt |
| nickname | VARCHAR(32) | 昵称 |
| created_at / updated_at | DATETIME | |

**account**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT NOT NULL, 索引 | |
| name | VARCHAR(32) NOT NULL | 账户名 |
| type | VARCHAR(16) NOT NULL | asset / liability |
| sub_type | VARCHAR(32) NOT NULL | cash / debit_card / wechat / alipay / yuebao / credit_card / huabei / custom |
| balance | DECIMAL(12,2) NOT NULL DEFAULT 0 | 冗余当前余额（负债为负，见 5.4） |
| initial_balance | DECIMAL(12,2) NOT NULL DEFAULT 0 | 初始余额（负债账户为初始欠款的相反数） |
| remark | VARCHAR(255) | 备注 |
| is_archived | TINYINT(1) NOT NULL DEFAULT 0 | 归档标记 |
| sort_order | INT NOT NULL DEFAULT 0 | 排序 |
| created_at / updated_at | DATETIME | |

**category**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT NOT NULL, 索引 | |
| parent_id | BIGINT NULL | NULL = 一级分类 |
| name | VARCHAR(32) NOT NULL | |
| kind | VARCHAR(8) NOT NULL | income / expense |
| is_system | TINYINT(1) NOT NULL DEFAULT 0 | 1 = 预置复制的分类 |
| created_at / updated_at | DATETIME | 同级名称查重由应用层保证 |

**transaction_record**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT NOT NULL | |
| type | VARCHAR(8) NOT NULL | expense / income / transfer |
| from_account_id | BIGINT NULL | 钱流出的账户（支出、转账转出） |
| to_account_id | BIGINT NULL | 钱流入的账户（收入、转账转入） |
| amount | DECIMAL(12,2) NOT NULL | 金额 > 0 |
| category_id | BIGINT NULL | NULL = 未分类；转账为 NULL |
| occurred_at | DATETIME NOT NULL | 交易时间，索引 (user_id, occurred_at) |
| counterparty | VARCHAR(128) | 交易对方（导入时填充） |
| item | VARCHAR(255) | 商品说明 / 备注 |
| source | VARCHAR(8) NOT NULL | manual / alipay / wechat |
| external_id | VARCHAR(64) NULL | 平台交易单号，重复检测依据 |
| import_batch_id | BIGINT NULL | 所属导入批次 |
| is_deleted | TINYINT(1) NOT NULL DEFAULT 0 | 软删除 |
| created_at / updated_at | DATETIME | |

**import_batch**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT NOT NULL | |
| platform | VARCHAR(8) NOT NULL | alipay / wechat |
| file_name | VARCHAR(255) NOT NULL | |
| record_count | INT NOT NULL | 入账笔数 |
| total_amount | DECIMAL(14,2) NOT NULL | 入账金额合计 |
| status | VARCHAR(12) NOT NULL | confirmed / rolled_back |
| imported_at | DATETIME | 入账时间 |
| rolled_back_at | DATETIME NULL | 撤销时间 |

### 5.3 E-R 关系

- user 1—n account、category、transaction_record、import_batch
- category 自关联 1—n category（两级树）
- import_batch 1—n transaction_record
- category 1—n transaction_record
- account 经 from_account_id / to_account_id 两个关联 1—n transaction_record

### 5.4 关键数据规则

1. **余额联动**：账户余额不在业务代码中读改写，一律由 SQL 原子增减（`balance = balance ± 金额`），事务保证流水与余额同生共死。
2. **负债账户余额为负**：欠款以负数存储（如信用卡欠款 5000 元 → balance = -5000），界面上以正数展示欠款。由此统一记账规则：**任何流水 = from 账户减、to 账户加**。支出仅有 from；收入仅有 to；转账两者都有。
3. **净资产 = SUM(balance)** 对全部账户直接成立，无需分类汇总。
4. **重复检测不设数据库唯一约束**：撤销批次后流水仍占用历史行，若建唯一索引会挡住同一文件的再次导入。重复检测在应用层完成——比对 `is_deleted = 0` 的同源记录与当前批次内记录。

## 6. 接口设计（REST API）

统一约定：请求/响应均为 JSON；成功返回 `{"code":0,"message":"ok","data":...}`；业务失败 `code` 为非 0 错误码；鉴权方式为请求头 `Authorization: Bearer <token>`。

**认证**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录，返回 token + 用户信息 |

**账户**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/accounts | 账户列表 + 净资产汇总 |
| POST | /api/accounts | 新建账户 |
| PUT | /api/accounts/{id} | 编辑账户 |
| DELETE | /api/accounts/{id} | 删除账户（无交易才允许） |
| PUT | /api/accounts/{id}/archive | 归档 / 恢复 |

**分类**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/categories?kind= | 分类树 |
| POST | /api/categories | 新增分类 |
| PUT | /api/categories/{id} | 编辑分类 |
| DELETE | /api/categories/{id} | 删除分类 |

**流水**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/transactions | 分页查询，参数：时间范围/类型/账户/分类/关键字 |
| POST | /api/transactions | 记一笔（支出/收入/转账通用） |
| PUT | /api/transactions/{id} | 编辑流水 |
| DELETE | /api/transactions/{id} | 删除流水（软删除 + 冲销） |

**账单导入**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/imports/preview | multipart：platform + file + 默认账户；返回解析预览，不落库 |
| POST | /api/imports/confirm | 提交勾选行，返回批次 id |
| GET | /api/imports | 批次列表 |
| GET | /api/imports/{id} | 批次明细 |
| POST | /api/imports/{id}/rollback | 批次撤销 |

**统计**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/stats/summary | 收入/支出/结余汇总 |
| GET | /api/stats/trend | 收支趋势（按月/按日） |
| GET | /api/stats/category | 分类构成聚合 |

## 7. 关键业务流程

### 7.1 记账（支出/收入/转账通用）

1. 前端提交 → controller 参数校验（金额 > 0、转账两账户不同且必须属于当前用户、账户未归档）；
2. service 开启事务：
   - 插入 transaction_record（支出记 from，收入记 to，转账记 from + to）；
   - 执行余额更新：from 账户 `balance -= amount`，to 账户 `balance += amount`；
3. 事务提交；任一步失败整体回滚，余额与流水永不分家。

### 7.2 编辑流水（支持更换账户、变更类型）

事务内两步法：

1. **旧记录反向冲销**：按旧记录执行 `旧 from += amount`、`旧 to -= amount`；
2. **新记录入账**：按新值执行 `新 from -= amount`、`新 to += amount`，同时更新流水各字段。

两步法天然支持任意字段变更（含类型 expense ↔ income ↔ transfer、换账户），且余额始终等于未删除流水之和。

### 7.3 删除流水

事务内：反向冲销余额（同 7.2 第一步）→ `is_deleted = 1`。流水保留，满足可追溯。

### 7.4 账单导入（上传—预览—确认—入账）

1. 上传 CSV → 按平台选择解析器（`AlipayCsvParser` / `WechatCsvParser`，实现统一 `BillParser` 接口）解析为标准化行；
2. 清洗过滤：非成功交易、金额非法行剔除并记录原因；
3. 分类映射：内置映射规则将平台分类映射为本系统分类，无匹配归未分类；
4. 账户匹配：按支付方式匹配用户账户，匹配不到留空；
5. 重复检测：external_id 比对库中未删除记录与批内记录，标记默认不勾选；
6. 返回预览（不落库），用户修改分类/账户并勾选；
7. 确认入账：单事务——插入 import_batch → 批量插入流水 → 逐条执行余额更新。批次状态 confirmed。

### 7.5 批次撤销

事务内：

1. 校验批次归属当前用户且状态为 confirmed（幂等约束，重复撤销直接拒绝）；
2. 批次内全部流水 `is_deleted = 1`；
3. 逐条反向冲销余额（from 加回、to 减回）；
4. 批次置为 rolled_back，记录 rolled_back_at。

撤销后该文件可重新导入（重复检测只比对未删除记录）。

## 8. 错误处理设计

- **后端**：全局异常处理器——业务异常抛自定义 `BizException`（携带错误码），统一返回 `Result.fail`；参数校验失败返回统一 400 格式；未捕获异常返回 500 并记录日志。
- **前端**：axios 响应拦截器统一处理——401 清除 token 跳登录页；其余错误弹出后端返回的错误信息。
- **导入错误**：文件非 CSV、表头与所选平台不匹配时，返回明确提示（"文件格式与所选平台不符"）；解析失败的行在预览页标注原因且默认不勾选。

## 9. 测试策略

- **白盒测试**（JUnit 5 + Spring Boot Test，重点覆盖）：
  - 记账三种类型的余额联动正确性；
  - 编辑流水（含换账户、换类型）的冲销 + 入账结果；
  - 删除流水冲销；批次撤销后余额完整还原；
  - 支付宝/微信 CSV 解析（各备一份脱敏样例文件）、分类映射、重复检测；
  - 事务回滚（构造异常验证余额与流水同时失败）。
- **黑盒测试**：MockMvc 接口测试覆盖全部 API；前端页面按 3.1–3.7 功能点逐条编写手工测试用例表（论文第 6 章素材）。

## 10. 设计决策记录

| # | 决策 | 理由 |
|---|---|---|
| 1 | 账户表冗余余额 + 事务联动更新 | 性能；且"余额联动、冲销重算"是开题研究点（3），论文有内容可写 |
| 2 | 交易统一用 from/to 双账户表达 | 支出/收入/转账共用一套余额更新规则（from 减、to 加），逻辑高度统一 |
| 3 | 负债账户余额为负 | 与决策 2 配合后，还款、负债消费全部自洽，净资产直接求和 |
| 4 | 流水软删除 | 满足可追溯性要求；支持批次撤销与重复检测语义 |
| 5 | 重复检测应用层实现，不设唯一索引 | 避免撤销后同一文件无法再导入 |
| 6 | 预置分类注册时复制到用户 | 查询逻辑统一；预置/自定义权限用 is_system 区分，避免全局共享分类的复杂性 |
| 7 | JWT 无状态鉴权 | 契合前后端分离架构，免服务端会话存储 |
| 8 | 导入预览不落库 | 避免脏数据入库；确认环节单事务批量入账保证一致性 |
| 9 | 不实现预算管理等功能 | 开题范围之外，YAGNI，保证四大核心模块聚焦 |
