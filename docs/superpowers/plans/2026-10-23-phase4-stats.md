# P4 统计报表模块实施计划（汇总 / 趋势 / 净资产 / 分类构成 / 仪表盘 / 分析页）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现统计报表模块（UC-5.1 ~ UC-5.7）：四个只读统计端点（/api/stats/summary|trend|net-worth|category）+ 首页仪表盘（浓缩视图）+ 分析页（完整视图，含账户总览与多维筛选），并清偿 P3 终审分诊的 import 模块遗留小修。

**Architecture:** 新增 `com.balancewise.stats` 包（controller/service/dto/vo/mapper），聚合查询走自定义 `StatsMapper`（@Select 按日汇总，Java 侧滚月/补零），口径全部只读 `is_deleted=0` 流水；净资产用「初始净额 + 流水累计」在 Java 侧重构（含归档账户）。前端新增 `useChart` ECharts 组合式函数，DashboardView（首页浓缩）与 StatsView（分析页完整）共用后端四个端点 + 既有 `/api/accounts`、`/api/transactions`。账户总览（UC-5.6）直接复用 P1 的 `GET /api/accounts`（AccountSummaryVo），不新增端点。

**Tech Stack:** Spring Boot 3.3 + MyBatis-Plus + JUnit 5 + MockMvc（H2 MySQL 模式）；Vue 3 + Element Plus + ECharts 5.6（package.json 已有依赖）。

## Global Constraints

- JDK 17：`C:\Program Files\Java\jdk-17`（每个 Bash 调用重新 `export JAVA_HOME` 与 PATH）
- Maven：`D:\KimiData\kimi\Workspaces\BalanceWise\.tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\KimiData\kimi\Workspaces\BalanceWise\.tools\maven-settings.xml -f backend/pom.xml`
- git 提交身份：`-c user.name="李东升" -c user.email="lids@local"`
- 中文源码与文档一律 UTF-8（含 Java/Vue/JS 源文件、Markdown、curl 请求体文件）
- 统一返回 `Result{code,message,data}`；code=0 成功、400 业务错误、401 仅 JWT 未登录（HTTP 状态也只有 401 走非 200，其余业务失败一律 HTTP 200 + code=400）
- **统计只读**：本阶段所有新代码不写任何账务数据，绝不修改 `account.balance`，绝不调用/复制 `TransactionService.applyBalance`；统计模块禁止出现 `@Transactional` 写操作
- **余额规则**：绝不新增余额变动路径（继承 P3 约束；统计与余额零耦合）
- 金额口径：全部 `BigDecimal`，输出前 `setScale(2, RoundingMode.HALF_UP)`；比较用 `compareTo`
- 时间边界与 P2 `TransactionService.buildWrapper` 完全一致：`start` 含当天 `00:00:00`（`>= start.atStartOfDay()`），`end` 排他上界（`< end.plusDays(1).atStartOfDay()`，含 end 当天 23:59:59.xxx）
- 统计口径：只统计 `is_deleted=0`；收支统计只含 `type IN ('expense','income')`（转账双向抵消、不进收支）；`category_id IS NULL` 归入「未分类」单列；分类构成按一级分类聚合、二级求和挂在 `children` 供下钻
- 净资产口径：某时点净资产 = Σ(asset 账户 initial_balance) − Σ(liability 账户 initial_balance) + Σ(该时点前 收入−支出)；**含归档账户**（归档仍持有余额）；`DATE(occurred_at)` 与 `type`/`category_id` 的聚合 SQL 必须同时兼容 MySQL 8 与 H2 MySQL 模式（测试环境）
- userId 隔离：所有统计查询必须带 `user_id = #{userId}`，数据绝不跨用户
- 测试约定：`@SpringBootTest @ActiveProfiles("test")` + 类级 `@Transactional`；H2 内存库（schema 由 `src/main/resources/schema.sql` 初始化）；基线 71 个用例（main @ 2adcce6）
- Git Bash 内嵌中文 JSON 会 GBK 乱码：含中文请求体写 UTF-8 文件 `curl -d @文件`
- Bash 不支持后台任务：后台进程用 `powershell Start-Process` 分离，验证完回收
- 阶段分支：`p4-stats`（从 main @ 2adcce6 切出）；阶段 tag：`p4-stats-v1`
- 前端约束：echarts 直接用 `import * as echarts from 'echarts'`（全量引入，package.json `echarts ^5.6.0` 已就位，**不新增依赖**）；请求统一走 `src/api/request.js`
- MainLayout 侧边菜单**已有** `/dashboard` 与 `/stats` 两个菜单项，本阶段只改 `router/index.js` 的路由指向，不动菜单与布局

---

## 文件结构（本阶段新增/修改清单）

**后端新增（`backend/src/main/java/com/balancewise/stats/`）：**

| 文件 | 职责 |
|---|---|
| `controller/StatsController.java` | 四个只读端点（§6 统计节），@CurrentUser 注入 userId |
| `service/StatsService.java` | 区间解析（月/日/补零）、汇总、趋势、净资产重构、分类聚合 |
| `dto/StatsTrendQuery.java` | trend/net-worth 查询参数：groupBy/months/start/end/accountId |
| `dto/StatsCategoryQuery.java` | category 查询参数：type/start/end/accountId |
| `vo/StatsSummaryVo.java` | {income, expense, balance} |
| `vo/TrendPointVo.java` | {label, income, expense, balance} |
| `vo/StatsTrendVo.java` | {points: List<TrendPointVo>} |
| `vo/NetWorthPointVo.java` | {label, netWorth} |
| `vo/StatsNetWorthVo.java` | {initialNetWorth, points: List<NetWorthPointVo>} |
| `vo/CategoryStatItemVo.java` | {categoryId, name, amount, percentage, children}（children 同为该类型，二级明细） |
| `vo/StatsCategoryVo.java` | {type, total, items: List<CategoryStatItemVo>} |
| `mapper/StatsMapper.java` | 三条聚合 @Select（按日+类型 / 按类型 / 按分类），全只读 |
| `mapper/row/TypeSumRow.java` | 投影行 {type, total} |
| `mapper/row/DailyFlowRow.java` | 投影行 {day: LocalDate, type, total} |
| `mapper/row/CategorySumRow.java` | 投影行 {categoryId, total} |

**后端修改（import 小修批次，Task 8）：**

| 文件 | 修改 |
|---|---|
| `importbill/service/ImportService.java` | confirm 增加批内 external_id 互重校验 |
| `importbill/service/AccountMatcher.java` | isArchived 拆箱防御（Integer.valueOf(0).equals） |
| `importbill/service/CategoryMappingService.java` | 类注释去掉不实描述 |

**后端测试新增/追加：**

| 文件 | 说明 |
|---|---|
| `src/test/java/com/balancewise/stats/StatsServiceTest.java` | 新建（Task 1~4 共用，逐步追加方法） |
| `src/test/java/com/balancewise/stats/StatsApiTest.java` | 新建（Task 5 MockMvc） |
| `importbill/ImportConfirmTest.java` | 追加批内互重用例 |
| `importbill/ImportBatchTest.java` | 追加撤销后重导用例 |
| `importbill/ImportPreviewTest.java` | 追加不支持平台 + 跨用户隔离用例 |
| `importbill/ImportMappingTest.java` | 追加 transfer→null 用例 |

**前端新增/修改：**

| 文件 | 说明 |
|---|---|
| `frontend/src/composables/useChart.js` | 新建：ECharts 实例生命周期组合式函数（Task 6 建，Task 7 复用） |
| `frontend/src/views/DashboardView.vue` | 新建：首页仪表盘（Task 6），替换 PlaceholderView |
| `frontend/src/views/StatsView.vue` | 新建：分析页（Task 7），替换 PlaceholderView |
| `frontend/src/router/index.js:11` | dashboard 路由指向 DashboardView.vue |
| `frontend/src/router/index.js:17` | stats 路由指向 StatsView.vue |
| `README.md` | 功能清单追加统计（Task 9） |

---

## 跨任务接口契约（Interfaces 总表）

**REST 端点（Task 5 产出，Task 6/7 消费；全部需 `Authorization: Bearer <token>`）：**

| 方法 | 路径 | 参数 | 返回 data |
|---|---|---|---|
| GET | /api/stats/summary | `start`,`end`（可选，yyyy-MM-dd），`accountId`（可选） | `StatsSummaryVo{income, expense, balance}` |
| GET | /api/stats/trend | `groupBy`=month\|day（默认 month）；`months`=1~24（month 快捷，默认 6）；`start`,`end`（day 必填，month 可选覆盖）；`accountId`（可选） | `StatsTrendVo{points:[{label, income, expense, balance}]}`，无数据期补零 |
| GET | /api/stats/net-worth | 同 trend（accountId 忽略：净资产是全账户口径） | `StatsNetWorthVo{initialNetWorth, points:[{label, netWorth}]}` |
| GET | /api/stats/category | `type`=expense\|income（默认 expense）；`start`,`end`（可选）；`accountId`（可选） | `StatsCategoryVo{type, total, items:[{categoryId, name, amount, percentage, children:[…]}]}`，未分类 categoryId=null |

**Service 签名（Task 1~4 产出）：**

```java
StatsSummaryVo summary(Long userId, LocalDate start, LocalDate end, Long accountId)
StatsTrendVo trend(Long userId, StatsTrendQuery q)
StatsNetWorthVo netWorth(Long userId, StatsTrendQuery q)
StatsCategoryVo category(Long userId, StatsCategoryQuery q)
```

**StatsMapper 签名（Task 1/2/4 产出，Task 1 建接口+sumByType，Task 2 加 dailyFlows，Task 4 加 sumByCategory）：**

```java
List<TypeSumRow> sumByType(Long userId, LocalDateTime from /*可空=无下界*/, LocalDateTime to /*排他*/, Long accountId /*可空*/)
List<DailyFlowRow> dailyFlows(Long userId, LocalDateTime from /*可空*/, LocalDateTime to, Long accountId /*可空*/)
List<CategorySumRow> sumByCategory(Long userId, String type, LocalDateTime from /*可空*/, LocalDateTime to, Long accountId /*可空*/)
```

**StatsService 私有助手（Task 2 产出，Task 3 在同一文件内复用）：** `resolveRange(StatsTrendQuery): Range`、`bucketLabels(Range): List<String>`（`yyyy-MM` 或 `yyyy-MM-dd`，首尾齐全）、`bucketEndDate(Range, String): LocalDate`、`record Range(String groupBy, LocalDate start, LocalDate end, LocalDateTime from, LocalDateTime to)`。

**既有接口复用（本阶段不改动）：** `GET /api/accounts` → `AccountSummaryVo{accounts, netWorth, assetTotal, liabilityTotal}`（UC-5.6 数据源）；`GET /api/transactions?size=10` → `PageResult{total, records:[TransactionVo]}`（仪表盘最近流水）；`TransactionService.create(1L, TransactionRequest)`（测试造数）。

**前端请求契约（Task 6/7 消费）：**

```js
request.get('/stats/summary', { params: { start, end, accountId? } })          // → res.data.{income,expense,balance}
request.get('/stats/trend', { params: { groupBy, months?, start?, end?, accountId? } })  // → res.data.points[]
request.get('/stats/net-worth', { params: { groupBy, months?, start?, end? } }) // → res.data.{initialNetWorth,points[]}
request.get('/stats/category', { params: { type, start, end, accountId? } })   // → res.data.{type,total,items[]}
useChart(): { el: Ref, setOption(opt), on(event, fn) }                          // Task 6 建，Task 7 复用
```

---

### Task 1: StatsMapper 接口 + summary 汇总卡片

**Files:**
- Create: `backend/src/main/java/com/balancewise/stats/mapper/StatsMapper.java`
- Create: `backend/src/main/java/com/balancewise/stats/mapper/row/TypeSumRow.java`
- Create: `backend/src/main/java/com/balancewise/stats/vo/StatsSummaryVo.java`
- Create: `backend/src/main/java/com/balancewise/stats/service/StatsService.java`
- Test: `backend/src/test/java/com/balancewise/stats/StatsServiceTest.java`

**Interfaces:**
- Consumes: 既有 `AccountService`/`TransactionService`（测试造数）、`Result` 风格。
- Produces: `StatsService.summary(Long, LocalDate, LocalDate, Long)`；`StatsSummaryVo{BigDecimal income, expense, balance}`；`StatsMapper.sumByType`；私有助手 `fromOf(LocalDate): LocalDateTime`、`toOf(LocalDate): LocalDateTime`。Task 2/3/4 在同一 Service 文件追加方法。

- [ ] **Step 1: 建分支**

```bash
cd D:\KimiData\kimi\Workspaces\BalanceWise
git checkout main && git checkout -b p4-stats
```

- [ ] **Step 2: 写失败测试（StatsServiceTest，新建）**

```java
package com.balancewise.stats;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
import com.balancewise.stats.service.StatsService;
import com.balancewise.stats.vo.StatsSummaryVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceTest {

    @Autowired
    private StatsService statsService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;

    private Account acc(String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    /** occurredAt 固定，避免依赖当前时间 */
    private void txn(String type, Long from, Long to, String amount, LocalDateTime at) {
        TransactionRequest r = new TransactionRequest();
        r.setType(type);
        r.setFromAccountId(from);
        r.setToAccountId(to);
        r.setAmount(new BigDecimal(amount));
        r.setOccurredAt(at);
        transactionService.create(1L, r);
    }

    @Test
    void emptyDataReturnsZeros() {
        StatsSummaryVo vo = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getIncome()));
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getExpense()));
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getBalance()));
    }

    @Test
    void aggregatesIncomeExpenseAndBalance() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("钱包", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "200.00", LocalDateTime.of(2026, 10, 5, 12, 0));
        txn("expense", a.getId(), null, "300.00", LocalDateTime.of(2026, 10, 6, 12, 0));
        txn("income", null, b.getId(), "5000.00", LocalDateTime.of(2026, 10, 7, 12, 0));
        // 区间外流水不进统计
        txn("expense", a.getId(), null, "999.00", LocalDateTime.of(2026, 11, 1, 12, 0));
        // 转账不进收支
        txn("transfer", a.getId(), b.getId(), "100.00", LocalDateTime.of(2026, 10, 8, 12, 0));

        StatsSummaryVo vo = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(0, new BigDecimal("5000.00").compareTo(vo.getIncome()));
        assertEquals(0, new BigDecimal("500.00").compareTo(vo.getExpense()));
        assertEquals(0, new BigDecimal("4500.00").compareTo(vo.getBalance()));
    }

    @Test
    void rangeAndAccountFilter() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 10, 5, 12, 0));
        txn("expense", a.getId(), null, "200.00", LocalDateTime.of(2026, 11, 5, 12, 0)); // 区间外
        txn("income", null, b.getId(), "1000.00", LocalDateTime.of(2026, 10, 6, 12, 0));

        StatsSummaryVo oct = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(0, new BigDecimal("100.00").compareTo(oct.getExpense()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(oct.getIncome()));

        // 账户筛选：只看账户 a → 只有支出 100（收入落在 b）
        StatsSummaryVo byA = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), a.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(byA.getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(byA.getIncome()));
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-17" && export PATH="$JAVA_HOME/bin:$PATH" && .tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=StatsServiceTest`
Expected: 编译失败（StatsService/StatsMapper/StatsSummaryVo 不存在）= 红灯

- [ ] **Step 4: 实现三个新建类**

`TypeSumRow.java`：

```java
package com.balancewise.stats.mapper.row;

import lombok.Data;

import java.math.BigDecimal;

/** 按类型汇总投影行（sumByType 结果） */
@Data
public class TypeSumRow {
    private String type;
    private BigDecimal total;
}
```

`StatsMapper.java`（包名落在 `@MapperScan("com.balancewise.**.mapper")` 扫描范围内；`<script>` 内条件可空，`from` 为空 = 无下界）：

```java
package com.balancewise.stats.mapper;

import com.balancewise.stats.mapper.row.TypeSumRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计聚合查询（UC-5.x）：全部只读。
 * 只统计 is_deleted=0 且 type IN ('expense','income') 的流水（转账双向抵消，不进收支）。
 * 时间边界与 P2 buildWrapper 一致：from 含（>=），to 排他（< 次日 00:00:00）。
 * DATE()/SUM()/GROUP BY 语法同时兼容 MySQL 8 与 H2 MySQL 模式。
 */
public interface StatsMapper {

    /** 区间内按类型汇总；from 可空（无下界），accountId 可空（命中 from 或 to 任一账户） */
    @Select("<script>" +
            "SELECT type, SUM(amount) AS total FROM transaction_record " +
            "WHERE user_id = #{userId} AND is_deleted = 0 AND type IN ('expense','income') " +
            "<if test='from != null'>AND occurred_at &gt;= #{from} </if>" +
            "AND occurred_at &lt; #{to} " +
            "<if test='accountId != null'>AND (from_account_id = #{accountId} OR to_account_id = #{accountId}) </if>" +
            "GROUP BY type" +
            "</script>")
    List<TypeSumRow> sumByType(@Param("userId") Long userId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("accountId") Long accountId);
}
```

`StatsSummaryVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 汇总卡片（UC-5.2）：区间内收入合计、支出合计、结余 = 收入 - 支出 */
@Data
public class StatsSummaryVo {
    private BigDecimal income;
    private BigDecimal expense;
    private BigDecimal balance;
}
```

`StatsService.java`（本任务只实现 summary 与边界助手；Task 2/3/4 在同一文件追加 trend/netWorth/category）：

```java
package com.balancewise.stats.service;

import com.balancewise.stats.mapper.StatsMapper;
import com.balancewise.stats.mapper.row.TypeSumRow;
import com.balancewise.stats.vo.StatsSummaryVo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计报表（UC-5.x）。纯只读：绝不写账务数据、绝不触碰 account.balance、绝不做余额联动。
 * 时间边界与 P2 TransactionService.buildWrapper 完全一致。
 */
@Service
public class StatsService {

    private final StatsMapper statsMapper;

    public StatsService(StatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    /** 区间汇总（UC-5.2）：start/end 为空 = 全部时间（上界取明天，保证含今天全部）；balance = income - expense */
    public StatsSummaryVo summary(Long userId, LocalDate start, LocalDate end, Long accountId) {
        List<TypeSumRow> rows = statsMapper.sumByType(userId, fromOf(start), toOf(end), accountId);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (TypeSumRow r : rows) {
            if ("income".equals(r.getType())) {
                income = income.add(r.getTotal());
            } else if ("expense".equals(r.getType())) {
                expense = expense.add(r.getTotal());
            }
        }
        StatsSummaryVo vo = new StatsSummaryVo();
        vo.setIncome(income.setScale(2, RoundingMode.HALF_UP));
        vo.setExpense(expense.setScale(2, RoundingMode.HALF_UP));
        vo.setBalance(income.subtract(expense).setScale(2, RoundingMode.HALF_UP));
        return vo;
    }

    /** start 含当天 00:00:00；null = 无下界 */
    private LocalDateTime fromOf(LocalDate start) {
        return start != null ? start.atStartOfDay() : null;
    }

    /** end 排他上界 < 次日 00:00:00（含 end 当天全部）；null = 含至今全部 */
    private LocalDateTime toOf(LocalDate end) {
        return end != null ? end.plusDays(1).atStartOfDay() : LocalDateTime.now().plusDays(1);
    }
}
```

- [ ] **Step 5: 跑测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 74/74（71 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 统计汇总卡片（StatsMapper 按类型聚合 + summary 只读服务）"
```

---

### Task 2: 收支趋势 trend（按月近 N 个月 / 按日自定义区间，补零）

**Files:**
- Create: `backend/src/main/java/com/balancewise/stats/dto/StatsTrendQuery.java`
- Create: `backend/src/main/java/com/balancewise/stats/mapper/row/DailyFlowRow.java`
- Create: `backend/src/main/java/com/balancewise/stats/vo/TrendPointVo.java`
- Create: `backend/src/main/java/com/balancewise/stats/vo/StatsTrendVo.java`
- Modify: `backend/src/main/java/com/balancewise/stats/mapper/StatsMapper.java`（追加 dailyFlows）
- Modify: `backend/src/main/java/com/balancewise/stats/service/StatsService.java`（追加 trend/resolveRange/Range/bucketLabels/bucketEndDate）
- Test: `backend/src/test/java/com/balancewise/stats/StatsServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 1 的 StatsMapper/StatsService/`fromOf`/`toOf`。
- Produces: `StatsTrendVo{List<TrendPointVo> points}`；`TrendPointVo{String label, BigDecimal income, expense, balance}`；`StatsTrendQuery{String groupBy="month", Integer months=6, LocalDate start/end, Long accountId}`；`StatsMapper.dailyFlows(userId, from, to, accountId)` 返回 `List<DailyFlowRow{LocalDate day, String type, BigDecimal total}>`（按 day 升序）；私有 `Range resolveRange(StatsTrendQuery)`、`List<String> bucketLabels(Range)`、`LocalDate bucketEndDate(Range, String)`。**Task 3 在同一文件复用这四个助手。**

- [ ] **Step 1: 写失败测试（StatsServiceTest 追加四个方法）**

```java
    private StatsTrendQuery trendQuery(String groupBy, Integer months, LocalDate start, LocalDate end) {
        StatsTrendQuery q = new StatsTrendQuery();
        q.setGroupBy(groupBy);
        q.setMonths(months);
        q.setStart(start);
        q.setEnd(end);
        return q;
    }

    @Test
    void trendValidatesGroupByAndDayRange() {
        // groupBy 非法
        BizException bad = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("week", null, null, null)));
        assertEquals("groupBy 必须是 month 或 day", bad.getMessage());
        // day 模式缺 start/end
        BizException noRange = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("day", null, null, null)));
        assertEquals("按日统计必须提供 start 和 end", noRange.getMessage());
        // start 晚于 end
        BizException reversed = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("day", null,
                        LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1))));
        assertEquals("start 不能晚于 end", reversed.getMessage());
        // 按日跨度超 366 天
        BizException tooLong = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("day", null,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 10))));
        assertEquals("按日统计区间不能超过 366 天", tooLong.getMessage());
        // months 超界
        BizException badMonths = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("month", 99, null, null)));
        assertEquals("months 必须在 1~24 之间", badMonths.getMessage());
    }

    @Test
    void trendByDayAggregatesAndZeroFills() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));
        txn("income", null, b.getId(), "400.00", LocalDateTime.of(2026, 10, 3, 9, 0));
        // 10-02 无数据 → 补零点；10-04 无数据 → 补零点

        StatsTrendVo vo = statsService.trend(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)));
        assertEquals(4, vo.getPoints().size());
        assertEquals("2026-10-01", vo.getPoints().get(0).getLabel());
        assertEquals("2026-10-04", vo.getPoints().get(3).getLabel());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, new BigDecimal("-100.00").compareTo(vo.getPoints().get(0).getBalance()));
        // 10-02 补零
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getIncome()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getBalance()));
        // 10-03 收入 400
        assertEquals(0, new BigDecimal("400.00").compareTo(vo.getPoints().get(2).getIncome()));
        // 区间外流水不进趋势
        txn("expense", a.getId(), null, "999.00", LocalDateTime.of(2026, 10, 5, 8, 30));
        StatsTrendVo after = statsService.trend(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)));
        assertEquals(4, after.getPoints().size());
        assertEquals(0, new BigDecimal("100.00").compareTo(after.getPoints().get(0).getExpense()));
    }

    @Test
    void trendByMonthWithExplicitRangeZeroFillsMiddle() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 9, 15, 12, 0));
        txn("expense", a.getId(), null, "200.00", LocalDateTime.of(2026, 11, 5, 12, 0));
        // 10 月无数据 → 补零桶

        StatsTrendVo vo = statsService.trend(1L, trendQuery("month", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30)));
        assertEquals(3, vo.getPoints().size());
        assertEquals("2026-09", vo.getPoints().get(0).getLabel());
        assertEquals("2026-10", vo.getPoints().get(1).getLabel());
        assertEquals("2026-11", vo.getPoints().get(2).getLabel());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getExpense()));
        assertEquals(0, new BigDecimal("200.00").compareTo(vo.getPoints().get(2).getExpense()));
        assertEquals(0, new BigDecimal("-200.00").compareTo(vo.getPoints().get(2).getBalance()));
    }

    @Test
    void trendSupportsAccountFilter() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));
        txn("income", null, b.getId(), "1000.00", LocalDateTime.of(2026, 10, 1, 9, 0));

        StatsTrendQuery q = trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
        q.setAccountId(a.getId());
        StatsTrendVo vo = statsService.trend(1L, q);
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(0).getIncome()));
    }
```

（文件头追加 import：`com.balancewise.common.BizException`、`com.balancewise.stats.dto.StatsTrendQuery`、`com.balancewise.stats.vo.StatsTrendVo`。）

- [ ] **Step 2: 跑测试确认红**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=StatsServiceTest`
Expected: 编译失败（StatsTrendQuery/TrendPointVo/StatsTrendVo/dailyFlows/trend 不存在）= 红灯

- [ ] **Step 3: 实现四个新建类 + StatsMapper 追加 + StatsService 追加**

`StatsTrendQuery.java`：

```java
package com.balancewise.stats.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 收支趋势 / 净资产趋势查询参数。month 模式用 months 近 N 个月快捷区间，或用 start/end 覆盖；day 模式必须 start+end 且跨度 ≤ 366 天 */
@Data
public class StatsTrendQuery {
    /** month / day，默认 month */
    private String groupBy = "month";
    /** month 快捷区间月数（1~24），默认 6；给了 start/end 时忽略 */
    private Integer months;
    /** 含当天 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;
    /** 含当天（排他上界实现） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;
    /** 命中 from 或 to 任一账户；净资产趋势忽略该参数（净资产是全账户口径） */
    private Long accountId;
}
```

`DailyFlowRow.java`：

```java
package com.balancewise.stats.mapper.row;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 按日+类型汇总投影行（dailyFlows 结果，按 day 升序） */
@Data
public class DailyFlowRow {
    private LocalDate day;
    private String type;
    private BigDecimal total;
}
```

`TrendPointVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 趋势单期：label 为 yyyy-MM（月）或 yyyy-MM-dd（日）；balance = income - expense */
@Data
public class TrendPointVo {
    private String label;
    private BigDecimal income;
    private BigDecimal expense;
    private BigDecimal balance;
}
```

`StatsTrendVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.util.List;

/** 收支趋势（UC-5.3）：按期排列，无数据期补零 */
@Data
public class StatsTrendVo {
    private List<TrendPointVo> points;
}
```

`StatsMapper.java` 追加（dailyFlows；from 为空 = 自时间起点累计，Task 3 净资产复用）：

```java
    /** 区间内按日+类型汇总，按 day 升序返回；from 可空（无下界） */
    @Select("<script>" +
            "SELECT DATE(occurred_at) AS day, type, SUM(amount) AS total FROM transaction_record " +
            "WHERE user_id = #{userId} AND is_deleted = 0 AND type IN ('expense','income') " +
            "<if test='from != null'>AND occurred_at &gt;= #{from} </if>" +
            "AND occurred_at &lt; #{to} " +
            "<if test='accountId != null'>AND (from_account_id = #{accountId} OR to_account_id = #{accountId}) </if>" +
            "GROUP BY DATE(occurred_at), type ORDER BY day" +
            "</script>")
    List<DailyFlowRow> dailyFlows(@Param("userId") Long userId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  @Param("accountId") Long accountId);
```

（文件头追加 import：`com.balancewise.stats.mapper.row.DailyFlowRow`。）

`StatsService.java` 追加：

```java
    /**
     * 收支趋势（UC-5.3）。month：按月（近 months 个月含当月，或 start~end 覆盖的月份逐一补零）；
     * day：按日（必须 start+end，跨度 ≤ 366 天）。每期 income/expense 聚合、balance = income - expense。
     */
    public StatsTrendVo trend(Long userId, StatsTrendQuery q) {
        Range range = resolveRange(q);
        List<DailyFlowRow> flows = statsMapper.dailyFlows(userId, range.from(), range.to(), q.getAccountId());
        StatsTrendVo vo = new StatsTrendVo();
        List<TrendPointVo> points = new ArrayList<>();
        for (String label : bucketLabels(range)) {
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            for (DailyFlowRow f : flows) {
                boolean inBucket = "month".equals(range.groupBy())
                        ? YearMonth.from(f.getDay()).equals(YearMonth.parse(label))
                        : f.getDay().equals(LocalDate.parse(label));
                if (inBucket) {
                    if ("income".equals(f.getType())) {
                        income = income.add(f.getTotal());
                    } else {
                        expense = expense.add(f.getTotal());
                    }
                }
            }
            TrendPointVo p = new TrendPointVo();
            p.setLabel(label);
            p.setIncome(income.setScale(2, RoundingMode.HALF_UP));
            p.setExpense(expense.setScale(2, RoundingMode.HALF_UP));
            p.setBalance(income.subtract(expense).setScale(2, RoundingMode.HALF_UP));
            points.add(p);
        }
        vo.setPoints(points);
        return vo;
    }

    /** 解析趋势区间：groupBy 校验 + 区间推导（month 快捷 = 近 months 个月含当月；显式 start/end 覆盖） */
    private Range resolveRange(StatsTrendQuery q) {
        String groupBy = q.getGroupBy() == null || q.getGroupBy().isBlank() ? "month" : q.getGroupBy();
        if (!"month".equals(groupBy) && !"day".equals(groupBy)) {
            throw new BizException("groupBy 必须是 month 或 day");
        }
        if ("day".equals(groupBy)) {
            if (q.getStart() == null || q.getEnd() == null) {
                throw new BizException("按日统计必须提供 start 和 end");
            }
            checkSpan(q.getStart(), q.getEnd(), ChronoUnit.DAYS, 366, "按日统计区间不能超过 366 天");
            return new Range(groupBy, q.getStart(), q.getEnd(),
                    q.getStart().atStartOfDay(), q.getEnd().plusDays(1).atStartOfDay());
        }
        if (q.getStart() != null && q.getEnd() != null) {
            checkSpan(q.getStart(), q.getEnd(), ChronoUnit.MONTHS, 24, "按月统计区间不能超过 24 个月");
            return new Range(groupBy, q.getStart(), q.getEnd(),
                    q.getStart().atStartOfDay(), q.getEnd().plusDays(1).atStartOfDay());
        }
        int months = q.getMonths() == null ? 6 : q.getMonths();
        if (months < 1 || months > 24) {
            throw new BizException("months 必须在 1~24 之间");
        }
        LocalDate now = LocalDate.now();
        LocalDate start = now.minusMonths(months - 1L).withDayOfMonth(1);
        return new Range(groupBy, start, now, start.atStartOfDay(), now.plusDays(1).atStartOfDay());
    }

    private void checkSpan(LocalDate start, LocalDate end, ChronoUnit unit, long max, String message) {
        if (start.isAfter(end)) {
            throw new BizException("start 不能晚于 end");
        }
        if (unit.between(start, end) > max) {
            throw new BizException(message);
        }
    }

    /** 桶标签全集（首尾齐全，无数据期也在内）：month → yyyy-MM；day → yyyy-MM-dd */
    private List<String> bucketLabels(Range r) {
        List<String> labels = new ArrayList<>();
        if ("month".equals(r.groupBy())) {
            YearMonth cursor = YearMonth.from(r.start());
            YearMonth last = YearMonth.from(r.end());
            while (!cursor.isAfter(last)) {
                labels.add(cursor.toString());
                cursor = cursor.plusMonths(1);
            }
        } else {
            for (LocalDate d = r.start(); !d.isAfter(r.end()); d = d.plusDays(1)) {
                labels.add(d.toString());
            }
        }
        return labels;
    }

    /** 桶的期末（含）：月桶 = 月末；日桶 = 当天。净资产累计「期初至今」用 */
    private LocalDate bucketEndDate(Range r, String label) {
        if ("month".equals(r.groupBy())) {
            return YearMonth.parse(label).atEndOfMonth();
        }
        return LocalDate.parse(label);
    }

    /** 解析后的统计区间：groupBy + 起止（含）+ SQL 边界（from 可空 = 无下界，to 排他） */
    private record Range(String groupBy, LocalDate start, LocalDate end,
                         LocalDateTime from, LocalDateTime to) {
    }
```

（文件头追加 import：`com.balancewise.common.BizException`、`com.balancewise.stats.dto.StatsTrendQuery`、`com.balancewise.stats.vo.StatsTrendVo`、`com.balancewise.stats.vo.TrendPointVo`、`java.time.YearMonth`、`java.time.temporal.ChronoUnit`、`java.util.ArrayList`。）

- [ ] **Step 4: 跑测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 78/78（74 既有 + 4 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 收支趋势（按月近N月/按日区间，补零，账户筛选）"
```

---

### Task 3: 净资产趋势 net-worth（初始余额 + 流水累计口径）

**Files:**
- Create: `backend/src/main/java/com/balancewise/stats/vo/NetWorthPointVo.java`
- Create: `backend/src/main/java/com/balancewise/stats/vo/StatsNetWorthVo.java`
- Modify: `backend/src/main/java/com/balancewise/stats/service/StatsService.java`（追加 netWorth；构造器注入 AccountMapper）
- Test: `backend/src/test/java/com/balancewise/stats/StatsServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 2 的 `resolveRange`/`bucketLabels`/`bucketEndDate`/`Range`、`StatsMapper.dailyFlows(from 传 null = 无下界)`；`AccountMapper`（`@MapperScan` 既有）。
- Produces: `StatsNetWorthVo{BigDecimal initialNetWorth, List<NetWorthPointVo> points}`；`NetWorthPointVo{String label, BigDecimal netWorth}`。

口径（规格 §5.4 规则 2/3 + UC-2.5）：`netWorth(T) = Σ(asset.initial_balance) − Σ(liability.initial_balance) + Σ(occurred_at ≤ T 的 收入−支出)`。负债账户 `initial_balance` 存正数（`AccountService.create` 只对 `balance` 取负），故初始净额要减负债。归档账户含在内（仍持有余额）。转账双向抵消自然不进累计。

- [ ] **Step 1: 写失败测试（StatsServiceTest 追加三个方法）**

```java
    @Test
    void netWorthReconstructsFromInitialAndFlows() {
        // 储蓄卡 initial 1000（资产）；信用卡 initial 500（负债 → 净初始 = 1000 - 500 = 500）
        Account card = acc("储蓄卡", "asset", "debit_card", "1000.00");
        Account credit = acc("信用卡", "liability", "credit_card", "500.00");
        txn("expense", card.getId(), null, "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));
        txn("income", null, card.getId(), "700.00", LocalDateTime.of(2026, 10, 2, 9, 0));

        StatsNetWorthVo vo = statsService.netWorth(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        assertEquals(0, new BigDecimal("500.00").compareTo(vo.getInitialNetWorth()));
        assertEquals(3, vo.getPoints().size());
        // T=10-01：500 - 100 = 400；T=10-02：400 + 700 = 1100；T=10-03 无流水 → 持平 1100
        assertEquals(0, new BigDecimal("400.00").compareTo(vo.getPoints().get(0).getNetWorth()));
        assertEquals(0, new BigDecimal("1100.00").compareTo(vo.getPoints().get(1).getNetWorth()));
        assertEquals(0, new BigDecimal("1100.00").compareTo(vo.getPoints().get(2).getNetWorth()));
    }

    @Test
    void netWorthIgnoresTransfersAndCountsFromBeginning() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        // 区间开始之前的流水必须计入（自时间起点累计）
        txn("income", null, a.getId(), "500.00", LocalDateTime.of(2026, 9, 1, 8, 30));
        // 转账双向抵消，不改变净资产
        txn("transfer", a.getId(), b.getId(), "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));

        StatsNetWorthVo vo = statsService.netWorth(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)));
        assertEquals(0, new BigDecimal("1000.00").compareTo(vo.getInitialNetWorth()));
        assertEquals(0, new BigDecimal("1500.00").compareTo(vo.getPoints().get(0).getNetWorth()));
    }

    @Test
    void netWorthByMonthBucketsCumulate() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 9, 15, 12, 0));
        txn("expense", a.getId(), null, "50.00", LocalDateTime.of(2026, 10, 2, 12, 0));

        StatsNetWorthVo vo = statsService.netWorth(1L, trendQuery("month", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)));
        assertEquals(0, new BigDecimal("1000.00").compareTo(vo.getInitialNetWorth()));
        assertEquals(2, vo.getPoints().size());
        assertEquals("2026-09", vo.getPoints().get(0).getLabel());
        assertEquals(0, new BigDecimal("900.00").compareTo(vo.getPoints().get(0).getNetWorth()));
        assertEquals(0, new BigDecimal("850.00").compareTo(vo.getPoints().get(1).getNetWorth()));
    }
```

（文件头追加 import：`com.balancewise.stats.vo.StatsNetWorthVo`。）

- [ ] **Step 2: 跑测试确认红**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=StatsServiceTest#netWorthReconstructsFromInitialAndFlows`
Expected: 编译失败（StatsNetWorthVo/netWorth 不存在）= 红灯

- [ ] **Step 3: 实现两个 VO + StatsService 追加**

`NetWorthPointVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 净资产趋势单期：label 为 yyyy-MM（月）或 yyyy-MM-dd（日）；netWorth = 期末净资产 */
@Data
public class NetWorthPointVo {
    private String label;
    private BigDecimal netWorth;
}
```

`StatsNetWorthVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 净资产趋势（UC-5.4）：initialNetWorth = 全部账户初始净额（负债取负）；points 按期累计 */
@Data
public class StatsNetWorthVo {
    private BigDecimal initialNetWorth;
    private List<NetWorthPointVo> points;
}
```

`StatsService.java`：构造器注入 `AccountMapper`，并追加 netWorth：

```java
    private final StatsMapper statsMapper;
    private final AccountMapper accountMapper;

    public StatsService(StatsMapper statsMapper, AccountMapper accountMapper) {
        this.statsMapper = statsMapper;
        this.accountMapper = accountMapper;
    }
```

（替换 Task 1 的单参构造器。文件头追加 import：`com.balancewise.account.mapper.AccountMapper`、`com.balancewise.stats.vo.NetWorthPointVo`、`com.balancewise.stats.vo.StatsNetWorthVo`。）

```java
    /**
     * 净资产趋势（UC-5.4 / 规格 5.4 规则 2、3）：
     * netWorth(期末) = Σ(asset.initial_balance) − Σ(liability.initial_balance) + Σ(期末前的 收入−支出)。
     * 含归档账户；转账双向抵消不进累计；区间起点前的流水计入（自时间起点累计，from 传 null）。
     */
    public StatsNetWorthVo netWorth(Long userId, StatsTrendQuery q) {
        Range range = resolveRange(q);
        List<Account> accounts = accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, userId));
        BigDecimal base = BigDecimal.ZERO;
        for (Account a : accounts) {
            BigDecimal initial = a.getInitialBalance() != null ? a.getInitialBalance() : BigDecimal.ZERO;
            base = base.add("liability".equals(a.getType()) ? initial.negate() : initial);
        }
        List<DailyFlowRow> flows = statsMapper.dailyFlows(userId, null, range.to(), null);

        StatsNetWorthVo vo = new StatsNetWorthVo();
        vo.setInitialNetWorth(base.setScale(2, RoundingMode.HALF_UP));
        List<NetWorthPointVo> points = new ArrayList<>();
        BigDecimal cum = base;
        int idx = 0; // flows 按 day 升序（SQL ORDER BY day），桶也按时间升序，双指针一遍累计
        for (String label : bucketLabels(range)) {
            LocalDate bucketEnd = bucketEndDate(range, label);
            while (idx < flows.size() && !flows.get(idx).getDay().isAfter(bucketEnd)) {
                DailyFlowRow f = flows.get(idx++);
                cum = cum.add("income".equals(f.getType()) ? f.getTotal() : f.getTotal().negate());
            }
            NetWorthPointVo p = new NetWorthPointVo();
            p.setLabel(label);
            p.setNetWorth(cum.setScale(2, RoundingMode.HALF_UP));
            points.add(p);
        }
        vo.setPoints(points);
        return vo;
    }
```

（文件头追加 import：`com.balancewise.account.entity.Account`、`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`。）

- [ ] **Step 4: 跑测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 81/81（78 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 净资产趋势（初始余额+流水累计重构口径，含归档账户）"
```

---

### Task 4: 分类构成 category（一级聚合 + 二级下钻 + 未分类单列）

**Files:**
- Create: `backend/src/main/java/com/balancewise/stats/dto/StatsCategoryQuery.java`
- Create: `backend/src/main/java/com/balancewise/stats/vo/CategoryStatItemVo.java`
- Create: `backend/src/main/java/com/balancewise/stats/vo/StatsCategoryVo.java`
- Create: `backend/src/main/java/com/balancewise/stats/mapper/row/CategorySumRow.java`
- Modify: `backend/src/main/java/com/balancewise/stats/mapper/StatsMapper.java`（追加 sumByCategory）
- Modify: `backend/src/main/java/com/balancewise/stats/service/StatsService.java`（追加 category；构造器注入 CategoryMapper）
- Test: `backend/src/test/java/com/balancewise/stats/StatsServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 1 的 `fromOf`/`toOf`；既有 `CategoryMapper`/`Category`（两级树，parentId）。
- Produces: `StatsCategoryVo{String type, BigDecimal total, List<CategoryStatItemVo> items}`；`CategoryStatItemVo{Long categoryId, String name, BigDecimal amount, BigDecimal percentage, List<CategoryStatItemVo> children}`（未分类的 categoryId = null）；`StatsCategoryQuery{String type="expense", LocalDate start/end, Long accountId}`；`StatsMapper.sumByCategory(userId, type, from, to, accountId)` 返回 `List<CategorySumRow{Long categoryId, BigDecimal total}>`。Task 5 的 controller 与 Task 6/7 的前端饼图直接消费。

口径：按一级分类聚合（子分类金额上卷到父级，`children` 保留二级明细供下钻）；`category_id IS NULL`、分类已删除、父级缺失的防御情形一律归入「未分类」单列；`percentage = amount / total × 100`（total=0 时全 0）；items 按金额降序。

- [ ] **Step 1: 写失败测试（StatsServiceTest 追加三个方法）**

```java
    @Autowired
    private com.balancewise.category.mapper.CategoryMapper categoryMapper;

    private com.balancewise.category.entity.Category cat(String name, String kind, Long parentId, Long userId) {
        com.balancewise.category.entity.Category c = new com.balancewise.category.entity.Category();
        c.setUserId(userId);
        c.setName(name);
        c.setKind(kind);
        c.setParentId(parentId);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return c;
    }

    /** 造数纪律：分类必须在 create 时带 categoryId（已记账流水再改分类会触发冲销，不用于统计造数） */
    private void expenseWithCategory(Account from, String amount, LocalDateTime at, Long categoryId) {
        com.balancewise.record.dto.TransactionRequest r = new com.balancewise.record.dto.TransactionRequest();
        r.setType("expense");
        r.setFromAccountId(from.getId());
        r.setAmount(new BigDecimal(amount));
        r.setOccurredAt(at);
        r.setCategoryId(categoryId);
        transactionService.create(1L, r);
    }

    @Test
    void categoryRollsUpToTopLevelWithChildren() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        var food = cat("餐饮", "expense", null, 1L);
        var meal = cat("三餐", "expense", food.getId(), 1L);
        var takeout = cat("外卖", "expense", food.getId(), 1L);
        // 餐饮一级：直接 10 + 三餐 30 + 外卖 20 = 60；未分类 15
        expenseWithCategory(a, "30.00", LocalDateTime.of(2026, 10, 1, 8, 0), meal.getId());
        expenseWithCategory(a, "20.00", LocalDateTime.of(2026, 10, 2, 8, 0), takeout.getId());
        expenseWithCategory(a, "10.00", LocalDateTime.of(2026, 10, 3, 8, 0), food.getId());
        expenseWithCategory(a, "15.00", LocalDateTime.of(2026, 10, 4, 8, 0), null);

        StatsCategoryQuery q = new StatsCategoryQuery();
        q.setType("expense");
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        StatsCategoryVo vo = statsService.category(1L, q);

        assertEquals(0, new BigDecimal("75.00").compareTo(vo.getTotal()));
        assertEquals(2, vo.getItems().size());
        // 降序：餐饮(60) 在前，未分类(15) 在后
        var top = vo.getItems().get(0);
        assertEquals("餐饮", top.getName());
        assertEquals(food.getId(), top.getCategoryId());
        assertEquals(0, new BigDecimal("60.00").compareTo(top.getAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(top.getPercentage()));
        assertEquals(2, top.getChildren().size());
        assertEquals("三餐", top.getChildren().get(0).getName());
        assertEquals(0, new BigDecimal("30.00").compareTo(top.getChildren().get(0).getAmount()));
        var uncategorized = vo.getItems().get(1);
        assertNull(uncategorized.getCategoryId());
        assertEquals("未分类", uncategorized.getName());
        assertEquals(0, new BigDecimal("15.00").compareTo(uncategorized.getAmount()));
    }

    @Test
    void categoryIsolatesByUserTypeAndTime() {
        Account a1 = acc("卡A", "asset", "debit_card", "1000.00");
        Account a2 = acc("卡B", "asset", "cash", "0.00");
        var mine = cat("餐饮", "expense", null, 1L);
        var others = cat("餐饮", "expense", null, 2L);
        com.balancewise.record.dto.TransactionRequest r1 = new com.balancewise.record.dto.TransactionRequest();
        r1.setType("expense"); r1.setFromAccountId(a1.getId()); r1.setAmount(new BigDecimal("100.00"));
        r1.setOccurredAt(LocalDateTime.of(2026, 10, 1, 8, 0)); r1.setCategoryId(mine.getId());
        transactionService.create(1L, r1);
        com.balancewise.record.dto.TransactionRequest r2 = new com.balancewise.record.dto.TransactionRequest();
        r2.setType("expense"); r2.setFromAccountId(a2.getId()); r2.setAmount(new BigDecimal("999.00"));
        r2.setOccurredAt(LocalDateTime.of(2026, 10, 1, 8, 0)); r2.setCategoryId(others.getId());
        transactionService.create(2L, r2);

        StatsCategoryQuery q = new StatsCategoryQuery();
        q.setType("expense");
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        StatsCategoryVo vo = statsService.category(1L, q);
        // user 2 的 999 绝不混入
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getTotal()));
        assertEquals(1, vo.getItems().size());

        // 时间过滤：只看 11 月 → 空
        q.setStart(LocalDate.of(2026, 11, 1));
        q.setEnd(LocalDate.of(2026, 11, 30));
        StatsCategoryVo empty = statsService.category(1L, q);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getTotal()));
        assertTrue(empty.getItems().isEmpty());

        // 收入分类 kind 不混进支出构成（type=expense 只查 expense 分类树，且 SQL 已限定 type）
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        q.setType("income");
        assertTrue(statsService.category(1L, q).getItems().isEmpty());
    }

    @Test
    void categoryValidatesType() {
        StatsCategoryQuery q = new StatsCategoryQuery();
        q.setType("transfer");
        BizException e = assertThrows(BizException.class, () -> statsService.category(1L, q));
        assertEquals("type 必须是 expense 或 income", e.getMessage());
    }
```

（文件头追加 import：`com.balancewise.stats.dto.StatsCategoryQuery`、`com.balancewise.stats.vo.StatsCategoryVo`。）

- [ ] **Step 2: 跑测试确认红**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=StatsServiceTest#categoryRollsUpToTopLevelWithChildren`
Expected: 编译失败（StatsCategoryQuery/CategoryStatItemVo/StatsCategoryVo/CategorySumRow/sumByCategory/category 不存在）= 红灯

- [ ] **Step 3: 实现四个新建类 + StatsMapper/StatsService 追加**

`StatsCategoryQuery.java`：

```java
package com.balancewise.stats.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 分类构成查询参数（UC-5.5）：type 决定支出/收入构成；start/end 可选（空 = 全部时间）；accountId 可选 */
@Data
public class StatsCategoryQuery {
    /** expense / income，默认 expense */
    private String type = "expense";
    /** 含当天 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;
    /** 含当天（排他上界实现） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;
    /** 命中 from 或 to 任一账户 */
    private Long accountId;
}
```

`CategoryStatItemVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 分类构成单节点：一级分类聚合值；children 为二级分类明细（下钻用）。未分类 categoryId = null */
@Data
public class CategoryStatItemVo {
    private Long categoryId;
    private String name;
    private BigDecimal amount;
    /** amount / 总计 × 100，两位小数 */
    private BigDecimal percentage;
    private List<CategoryStatItemVo> children;
}
```

`StatsCategoryVo.java`：

```java
package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 分类构成（UC-5.5）：按一级分类聚合，items 按金额降序，未分类单列 */
@Data
public class StatsCategoryVo {
    private String type;
    private BigDecimal total;
    private List<CategoryStatItemVo> items;
}
```

`CategorySumRow.java`：

```java
package com.balancewise.stats.mapper.row;

import lombok.Data;

import java.math.BigDecimal;

/** 按分类汇总投影行（sumByCategory 结果）；category_id 为 NULL = 未分类行 */
@Data
public class CategorySumRow {
    private Long categoryId;
    private BigDecimal total;
}
```

`StatsMapper.java` 追加（sumByCategory；转账无分类，SQL 直接限定单 type 即可）：

```java
    /** 区间内按分类汇总（单 type）；from 可空；category_id 为 NULL 的行保留（归入未分类） */
    @Select("<script>" +
            "SELECT category_id, SUM(amount) AS total FROM transaction_record " +
            "WHERE user_id = #{userId} AND is_deleted = 0 AND type = #{type} " +
            "<if test='from != null'>AND occurred_at &gt;= #{from} </if>" +
            "AND occurred_at &lt; #{to} " +
            "<if test='accountId != null'>AND (from_account_id = #{accountId} OR to_account_id = #{accountId}) </if>" +
            "GROUP BY category_id" +
            "</script>")
    List<CategorySumRow> sumByCategory(@Param("userId") Long userId,
                                       @Param("type") String type,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("accountId") Long accountId);
```

（文件头追加 import：`com.balancewise.stats.mapper.row.CategorySumRow`。）

`StatsService.java`：构造器再注入 `CategoryMapper`，并追加 category：

```java
    private final StatsMapper statsMapper;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;

    public StatsService(StatsMapper statsMapper, AccountMapper accountMapper, CategoryMapper categoryMapper) {
        this.statsMapper = statsMapper;
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
    }
```

（替换 Task 3 的双参构造器。文件头追加 import：`com.balancewise.category.entity.Category`、`com.balancewise.category.mapper.CategoryMapper`、`com.balancewise.stats.dto.StatsCategoryQuery`、`com.balancewise.stats.mapper.row.CategorySumRow`、`com.balancewise.stats.vo.CategoryStatItemVo`、`com.balancewise.stats.vo.StatsCategoryVo`、`java.util.LinkedHashMap`、`java.util.Map`、`java.util.stream.Collectors`。）

```java
    /**
     * 分类构成（UC-5.5）：按一级分类聚合（子分类上卷，children 保留二级明细供下钻）；
     * category_id 为 NULL / 分类已删 / 父级缺失 的行一律归入「未分类」单列；items 按金额降序。
     */
    public StatsCategoryVo category(Long userId, StatsCategoryQuery q) {
        String type = q.getType() == null || q.getType().isBlank() ? "expense" : q.getType();
        if (!"expense".equals(type) && !"income".equals(type)) {
            throw new BizException("type 必须是 expense 或 income");
        }
        List<CategorySumRow> rows = statsMapper.sumByCategory(
                userId, type, fromOf(q.getStart()), toOf(q.getEnd()), q.getAccountId());

        List<Category> cats = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, type));
        Map<Long, Category> byId = cats.stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        Map<Long, CategoryStatItemVo> topById = new LinkedHashMap<>();
        CategoryStatItemVo uncategorized = null;
        BigDecimal total = BigDecimal.ZERO;
        for (CategorySumRow row : rows) {
            total = total.add(row.getTotal());
            Long topId = resolveTopId(row.getCategoryId(), byId);
            if (topId == null) {
                if (uncategorized == null) {
                    uncategorized = statItem(null, "未分类");
                }
                uncategorized.setAmount(uncategorized.getAmount().add(row.getTotal()));
            } else {
                CategoryStatItemVo top = topById.computeIfAbsent(topId,
                        id -> statItem(id, byId.get(id).getName()));
                top.setAmount(top.getAmount().add(row.getTotal()));
                if (!topId.equals(row.getCategoryId())) {
                    CategoryStatItemVo child = top.getChildren().stream()
                            .filter(c -> row.getCategoryId().equals(c.getCategoryId()))
                            .findFirst().orElse(null);
                    if (child == null) {
                        child = statItem(row.getCategoryId(), byId.get(row.getCategoryId()).getName());
                        top.getChildren().add(child);
                    }
                    child.setAmount(child.getAmount().add(row.getTotal()));
                }
            }
        }

        List<CategoryStatItemVo> items = new ArrayList<>(topById.values());
        if (uncategorized != null) {
            items.add(uncategorized);
        }
        items.sort((x, y) -> y.getAmount().compareTo(x.getAmount()));
        BigDecimal finalTotal = total;
        for (CategoryStatItemVo it : items) {
            it.setAmount(it.getAmount().setScale(2, RoundingMode.HALF_UP));
            it.setPercentage(percentage(it.getAmount(), finalTotal));
            it.getChildren().forEach(c -> {
                c.setAmount(c.getAmount().setScale(2, RoundingMode.HALF_UP));
                c.setPercentage(percentage(c.getAmount(), finalTotal));
            });
            it.getChildren().sort((x, y) -> y.getAmount().compareTo(x.getAmount()));
        }

        StatsCategoryVo vo = new StatsCategoryVo();
        vo.setType(type);
        vo.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        vo.setItems(items);
        return vo;
    }

    /** 子分类 → 一级分类；NULL / 已删 / 父级缺失 → null（归入未分类） */
    private Long resolveTopId(Long categoryId, Map<Long, Category> byId) {
        if (categoryId == null) {
            return null;
        }
        Category c = byId.get(categoryId);
        if (c == null) {
            return null;
        }
        if (c.getParentId() == null) {
            return categoryId;
        }
        return byId.containsKey(c.getParentId()) ? c.getParentId() : null;
    }

    private CategoryStatItemVo statItem(Long categoryId, String name) {
        CategoryStatItemVo vo = new CategoryStatItemVo();
        vo.setCategoryId(categoryId);
        vo.setName(name);
        vo.setAmount(BigDecimal.ZERO);
        vo.setPercentage(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        vo.setChildren(new ArrayList<>());
        return vo;
    }

    private BigDecimal percentage(BigDecimal amount, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }
```

- [ ] **Step 4: 跑测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 84/84（81 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 分类构成（一级聚合/二级下钻/未分类单列，时间+账户筛选）"
```

---

### Task 5: StatsController 四个端点 + MockMvc 集成测试

**Files:**
- Create: `backend/src/main/java/com/balancewise/stats/controller/StatsController.java`
- Test: `backend/src/test/java/com/balancewise/stats/StatsApiTest.java`

**Interfaces:**
- Consumes: Task 1~4 的 `StatsService` 四个方法与两类 Query DTO；`AuthApiTest`/`ImportApiTest` 的 MockMvc 模式（`@AutoConfigureMockMvc` + `jwtUtil.generate(1L)`）。
- Produces: 规格 §6 统计节四个只读端点：`GET /api/stats/summary`、`GET /api/stats/trend`、`GET /api/stats/net-worth`、`GET /api/stats/category`。Task 6/7 前端消费。

- [ ] **Step 1: 写失败测试（StatsApiTest，新建）**

```java
package com.balancewise.stats;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatsApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private ObjectMapper objectMapper;

    private void seedOneExpense() {
        AccountRequest r = new AccountRequest();
        r.setName("统计卡");
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        Long accountId = accountService.create(1L, r).getId();
        TransactionRequest t = new TransactionRequest();
        t.setType("expense");
        t.setFromAccountId(accountId);
        t.setAmount(new BigDecimal("100.00"));
        t.setOccurredAt(LocalDateTime.of(2026, 10, 5, 12, 0));
        transactionService.create(1L, t);
    }

    @Test
    void summaryReturnsAggregatedShape() throws Exception {
        seedOneExpense();
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(get("/api/stats/summary")
                        .param("start", "2026-10-01").param("end", "2026-10-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, body.path("code").asInt());
        assertEquals(0, new BigDecimal("100.00").compareTo(
                body.path("data").path("expense").decimalValue()));
        assertTrue(body.path("data").has("income"));
        assertTrue(body.path("data").has("balance"));
    }

    @Test
    void allStatsEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/stats/summary")).andExpect(status().is(401));
        mockMvc.perform(get("/api/stats/trend").param("groupBy", "month")).andExpect(status().is(401));
        mockMvc.perform(get("/api/stats/net-worth").param("groupBy", "month")).andExpect(status().is(401));
        mockMvc.perform(get("/api/stats/category").param("type", "expense")).andExpect(status().is(401));
    }

    @Test
    void trendInvalidGroupByReturnsCode400() throws Exception {
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(get("/api/stats/trend")
                        .param("groupBy", "week")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(400, body.path("code").asInt());
        assertEquals("groupBy 必须是 month 或 day", body.path("message").asText());
    }

    @Test
    void categoryInvalidTypeReturnsCode400() throws Exception {
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(get("/api/stats/category")
                        .param("type", "transfer")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(400, body.path("code").asInt());
        assertEquals("type 必须是 expense 或 income", body.path("message").asText());
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=StatsApiTest`
Expected: 编译失败（StatsController 不存在）= 红灯

- [ ] **Step 3: 实现 StatsController**

```java
package com.balancewise.stats.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.stats.dto.StatsCategoryQuery;
import com.balancewise.stats.dto.StatsTrendQuery;
import com.balancewise.stats.service.StatsService;
import com.balancewise.stats.vo.StatsCategoryVo;
import com.balancewise.stats.vo.StatsNetWorthVo;
import com.balancewise.stats.vo.StatsSummaryVo;
import com.balancewise.stats.vo.StatsTrendVo;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 统计报表只读端点（UC-5.x，规格 §6 统计节）。全部只读，不触碰任何账务写路径。 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 汇总卡片（UC-5.2）：start/end 可选（yyyy-MM-dd，含当天），accountId 可选 */
    @GetMapping("/summary")
    public Result<StatsSummaryVo> summary(@CurrentUser Long userId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                          @RequestParam(required = false) Long accountId) {
        return Result.ok(statsService.summary(userId, start, end, accountId));
    }

    /** 收支趋势（UC-5.3）：groupBy=month（months 近 N 个月或 start~end）/ day（必须 start+end） */
    @GetMapping("/trend")
    public Result<StatsTrendVo> trend(@CurrentUser Long userId, StatsTrendQuery q) {
        return Result.ok(statsService.trend(userId, q));
    }

    /** 净资产趋势（UC-5.4）：参数同 trend（accountId 忽略：净资产是全账户口径） */
    @GetMapping("/net-worth")
    public Result<StatsNetWorthVo> netWorth(@CurrentUser Long userId, StatsTrendQuery q) {
        return Result.ok(statsService.netWorth(userId, q));
    }

    /** 分类构成（UC-5.5）：type=expense/income，按一级分类聚合，children 二级明细供下钻 */
    @GetMapping("/category")
    public Result<StatsCategoryVo> category(@CurrentUser Long userId, StatsCategoryQuery q) {
        return Result.ok(statsService.category(userId, q));
    }
}
```

- [ ] **Step 4: 跑测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 88/88（84 既有 + 4 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 统计 REST 四个只读端点与 MockMvc 集成测试（401/参数校验/汇总形状）"
```

---

### Task 6: 前端 DashboardView（首页仪表盘浓缩视图）+ useChart 组合式函数

**Files:**
- Create: `frontend/src/composables/useChart.js`
- Create: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/router/index.js:11`（dashboard 路由指向 DashboardView.vue）

**Interfaces:**
- Consumes: Task 5 端点 + 既有 `GET /api/accounts`、`GET /api/transactions?size=10`；`request`（`src/api/request.js`）。
- Produces: 路由 `/dashboard` 渲染 DashboardView；`useChart(): { el: Ref, setOption(opt), on(event, fn) }`（echarts 实例生命周期：init/dispose/resize/延迟 option/延迟事件绑定）。**Task 7 的 StatsView 复用 useChart，不改其签名。**

页面内容（UC-5.1 浓缩视图）：净资产卡片（含账户数）、本月收入卡片、本月支出卡片（含结余）、近 6 个月收支趋势迷你折线图、本月支出分类饼图（未分类与各级一级分类）、最近 10 条流水表格。

- [ ] **Step 1: 改路由（router/index.js 第 11 行）**

把：

```js
      { path: 'dashboard', component: () => import('../views/PlaceholderView.vue'), meta: { title: '首页仪表盘' } },
```

改为：

```js
      { path: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '首页仪表盘' } },
```

（MainLayout 菜单与 `/stats` 路由本任务不动；PlaceholderView 文件保留，Task 7 改完 stats 路由后它不再被引用。）

- [ ] **Step 2: 实现 useChart.js**

```js
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * ECharts 实例生命周期组合式函数。
 * 数据到达可能早于图表挂载（首屏并发请求），setOption/on 都做了挂起缓存。
 */
export function useChart() {
  const el = ref(null)
  let chart = null
  let pendingOption = null
  const pendingEvents = []
  const resize = () => { if (chart) chart.resize() }

  onMounted(() => {
    chart = echarts.init(el.value)
    window.addEventListener('resize', resize)
    pendingEvents.forEach(([ev, fn]) => chart.on(ev, fn))
    pendingEvents.length = 0
    if (pendingOption) {
      chart.setOption(pendingOption, true)
      pendingOption = null
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    if (chart) chart.dispose()
    chart = null
  })

  const setOption = opt => {
    if (chart) chart.setOption(opt, true)
    else pendingOption = opt
  }

  const on = (ev, fn) => {
    if (chart) chart.on(ev, fn)
    else pendingEvents.push([ev, fn])
  }

  return { el, setOption, on }
}
```

- [ ] **Step 3: 实现 DashboardView.vue**

```vue
<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">净资产</div>
          <div class="stat-value">¥ {{ money(netWorth) }}</div>
          <div class="stat-sub">共 {{ accountCount }} 个账户</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">本月收入</div>
          <div class="stat-value income">¥ {{ money(month.income) }}</div>
          <div class="stat-sub">{{ monthStart }} ~ {{ today }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">本月支出</div>
          <div class="stat-value expense">¥ {{ money(month.expense) }}</div>
          <div class="stat-sub">结余 ¥ {{ money(month.balance) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header><span>近 6 个月收支趋势</span></template>
          <div ref="trendEl" class="chart trend" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header><span>本月支出构成</span></template>
          <div v-show="pieItems.length" ref="pieEl" class="chart pie" />
          <el-empty v-if="!pieItems.length" description="本月暂无支出" :image-size="70" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          <span>最近流水</span>
          <el-button size="small" text type="primary" @click="router.push('/transactions')">查看全部</el-button>
        </div>
      </template>
      <el-table :data="recent" size="small">
        <el-table-column prop="occurredAt" label="时间" width="165" />
        <el-table-column label="类型" width="76">
          <template #default="{ row }">
            <el-tag size="small"
                    :type="row.type === 'expense' ? 'danger' : row.type === 'income' ? 'success' : 'info'">
              {{ { expense: '支出', income: '收入', transfer: '转账' }[row.type] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ row.categoryName || '未分类' }}</template>
        </el-table-column>
        <el-table-column label="账户" min-width="150">
          <template #default="{ row }">
            {{ row.type === 'transfer'
              ? `${row.fromAccountName || ''} → ${row.toAccountName || ''}`
              : row.type === 'expense' ? (row.fromAccountName || '—') : (row.toAccountName || '—') }}
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <span :class="row.type === 'expense' ? 'expense' : 'income'">{{ money(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="counterparty" label="对方" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterparty || '—' }}</template>
        </el-table-column>
        <template #empty><el-empty description="暂无流水" :image-size="60" /></template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api/request'
import { useChart } from '../composables/useChart'

const router = useRouter()
const netWorth = ref(0)
const accountCount = ref(0)
const month = reactive({ income: 0, expense: 0, balance: 0 })
const recent = ref([])
const pieItems = ref([])

const { el: trendEl, setOption: setTrend } = useChart()
const { el: pieEl, setOption: setPie } = useChart()

const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pad = n => String(n).padStart(2, '0')
const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

const now = new Date()
const monthStart = fmtDate(new Date(now.getFullYear(), now.getMonth(), 1))
const today = fmtDate(now)

onMounted(async () => {
  const [acc, sum, trend, pie, txn] = await Promise.all([
    request.get('/accounts'),
    request.get('/stats/summary', { params: { start: monthStart, end: today } }),
    request.get('/stats/trend', { params: { groupBy: 'month', months: 6 } }),
    request.get('/stats/category', { params: { type: 'expense', start: monthStart, end: today } }),
    request.get('/transactions', { params: { size: 10 } }),
  ])
  netWorth.value = acc.data.netWorth
  accountCount.value = acc.data.accounts.length
  Object.assign(month, sum.data)
  recent.value = txn.data.records
  pieItems.value = pie.data.items

  setTrend({
    tooltip: { trigger: 'axis', valueFormatter: v => money(v) },
    legend: { data: ['收入', '支出'], bottom: 0 },
    grid: { left: 70, right: 20, top: 20, bottom: 44 },
    xAxis: { type: 'category', data: trend.data.points.map(p => p.label) },
    yAxis: { type: 'value' },
    series: [
      { name: '收入', type: 'line', smooth: true, data: trend.data.points.map(p => Number(p.income)) },
      { name: '支出', type: 'line', smooth: true, data: trend.data.points.map(p => Number(p.expense)) },
    ],
  })
  if (pieItems.value.length) {
    setPie({
      tooltip: { trigger: 'item', valueFormatter: v => money(v) },
      legend: { bottom: 0, type: 'scroll' },
      series: [{
        type: 'pie',
        radius: ['36%', '60%'],
        center: ['50%', '42%'],
        data: pieItems.value.map(i => ({ name: i.name, value: Number(i.amount) })),
        label: { formatter: '{b}\n{d}%' },
      }],
    })
  }
})
</script>

<style scoped>
.stat-card { min-height: 108px; }
.stat-label { color: #8a90a3; font-size: 13px; }
.stat-value { font-size: 26px; font-weight: 700; margin: 8px 0 4px; }
.stat-value.income, .income { color: #2f9e6e; }
.stat-value.expense, .expense { color: #e05252; }
.stat-sub { color: #8a90a3; font-size: 12px; }
.chart.trend { height: 280px; }
.chart.pie { height: 280px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
```

- [ ] **Step 4: 构建 + 起服务目检 + 提交**

Run: `cd frontend && npm run build`
Expected: 构建成功（无 echarts/导入报错）

起 dev server 目检（可选但建议；后台分离、验证完回收）：

```bash
powershell Start-Process -WindowStyle Hidden -FilePath "npm" -ArgumentList "run","dev" -WorkingDirectory "D:\KimiData\kimi\Workspaces\BalanceWise\frontend"
```

浏览器打开 `http://localhost:5173/dashboard`：三张卡片有数、趋势图两条线、饼图渲染、最近流水 10 行（新用户无数据时卡片为 0.00、饼图显示「本月暂无支出」空态）。验证完回收进程：

```powershell
Get-Process node -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match 'vite' } | Stop-Process -Force
```

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 首页仪表盘（净资产/本月收支/近6月趋势/支出构成/最近流水，ECharts）"
```

---

### Task 7: 前端 StatsView 分析页（完整视图：多维筛选 + 趋势 + 构成下钻 + 账户总览）

**Files:**
- Create: `frontend/src/views/StatsView.vue`
- Modify: `frontend/src/router/index.js:17`（stats 路由指向 StatsView.vue）

**Interfaces:**
- Consumes: Task 5 四端点；Task 6 的 `useChart()`；既有 `GET /api/accounts`（**UC-5.6 账户总览数据源——后端无需新增端点**：AccountSummaryVo 已含 `accounts/netWorth/assetTotal/liabilityTotal`，本页只做可视化）。
- Produces: 路由 `/stats` 渲染 StatsView（分析页完整视图）。

页面内容（UC-5.2~5.7 完整视图）：
- **多维筛选（UC-5.7）**：时间（本月/上月/今年/自定义区间）、账户（全部/单个，命中 from 或 to）、趋势粒度（按月/按日）；类型维度由构成图「支出构成/收入构成」切换承担。
- **汇总卡片（UC-5.2）**：收入合计 / 支出合计 / 结余（红绿着色）。
- **收支趋势折线（UC-5.3）**：收入/支出/结余三条线；按日粒度受后端 366 天上限约束（超限弹后端错误提示）。
- **分类构成饼图（UC-5.5）**：一级分类；点击扇区下钻二级（有返回按钮）；未分类单列。
- **账户总览（UC-5.6）**：各账户余额横向条形图（负债为负、红色）+ 资产/负债占比饼图 + 净资产汇总文字。

账户总览合并说明：UC-5.6 的后端数据在 P1 已随 `GET /api/accounts` 提供（`AccountSummaryVo`），统计模块纯复用，故不设独立后端任务，本任务一并完成前端可视化——这即原计划 Task 8 的归并结果。

- [ ] **Step 1: 改路由（router/index.js 第 17 行）**

把：

```js
      { path: 'stats', component: () => import('../views/PlaceholderView.vue'), meta: { title: '统计报表' } },
```

改为：

```js
      { path: 'stats', component: () => import('../views/StatsView.vue'), meta: { title: '统计报表' } },
```

（改完后 PlaceholderView.vue 不再被任何路由引用，但文件保留不删，避免无关 diff。）

- [ ] **Step 2: 实现 StatsView.vue**

```vue
<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 16px">
      <div class="filter-bar">
        <el-radio-group v-model="preset" @change="loadAll">
          <el-radio-button value="month">本月</el-radio-button>
          <el-radio-button value="lastMonth">上月</el-radio-button>
          <el-radio-button value="year">今年</el-radio-button>
          <el-radio-button value="custom">自定义</el-radio-button>
        </el-radio-group>
        <el-date-picker v-if="preset === 'custom'" v-model="customRange" type="daterange"
                        value-format="YYYY-MM-DD" range-separator="~"
                        start-placeholder="开始日期" end-placeholder="结束日期"
                        style="width: 250px" @change="loadAll" />
        <el-select v-model="accountId" filterable clearable placeholder="全部账户"
                   style="width: 160px" @change="loadAll">
          <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
        </el-select>
        <el-radio-group v-model="granularity" @change="loadAll">
          <el-radio-button value="month">按月</el-radio-button>
          <el-radio-button value="day">按日</el-radio-button>
        </el-radio-group>
      </div>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">收入合计</div>
          <div class="stat-value income">¥ {{ money(summary.income) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">支出合计</div>
          <div class="stat-value expense">¥ {{ money(summary.expense) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">结余</div>
          <div class="stat-value" :class="Number(summary.balance) < 0 ? 'expense' : 'income'">
            ¥ {{ money(summary.balance) }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header><span>收支趋势（{{ granularity === 'month' ? '按月' : '按日' }}）</span></template>
          <div ref="trendEl" class="chart lg" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>
            <div class="card-head">
              <el-radio-group v-model="catType" size="small" @change="loadCategory">
                <el-radio-button value="expense">支出构成</el-radio-button>
                <el-radio-button value="income">收入构成</el-radio-button>
              </el-radio-group>
              <el-button v-if="drill" size="small" text type="primary" @click="drill = null">← 返回一级</el-button>
            </div>
          </template>
          <div v-show="catItems.length" ref="pieEl" class="chart lg" />
          <el-empty v-if="!catItems.length" description="该区间暂无数据" :image-size="70" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header><span>账户余额对比</span></template>
          <div ref="barEl" class="chart md" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header><span>资产 / 负债占比</span></template>
          <div ref="ratioEl" class="chart md" />
          <div class="ratio-text">
            净资产 ¥ {{ money(accSummary.netWorth) }}<br>
            资产合计 ¥ {{ money(accSummary.assetTotal) }} ／ 负债合计 ¥ {{ money(accSummary.liabilityTotal) }}
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import request from '../api/request'
import { useChart } from '../composables/useChart'

// ===== 多维筛选（UC-5.7）=====
const preset = ref('month')
const customRange = ref(null)
const accountId = ref(null)
const granularity = ref('month')
const accounts = ref([])

const summary = reactive({ income: 0, expense: 0, balance: 0 })
const catType = ref('expense')
const catItems = ref([])
const drill = ref(null)
const accSummary = reactive({ netWorth: 0, assetTotal: 0, liabilityTotal: 0, accounts: [] })

const { el: trendEl, setOption: setTrend } = useChart()
const { el: pieEl, setOption: setPie, on: onPie } = useChart()
const { el: barEl, setOption: setBar } = useChart()
const { el: ratioEl, setOption: setRatio } = useChart()

const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pad = n => String(n).padStart(2, '0')
const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

const range = computed(() => {
  const now = new Date()
  if (preset.value === 'month') {
    return { start: fmtDate(new Date(now.getFullYear(), now.getMonth(), 1)), end: fmtDate(now) }
  }
  if (preset.value === 'lastMonth') {
    const first = new Date(now.getFullYear(), now.getMonth(), 1)
    const last = new Date(now.getFullYear(), now.getMonth(), 0)
    return { start: fmtDate(new Date(last.getFullYear(), last.getMonth(), 1)), end: fmtDate(last) }
  }
  if (preset.value === 'year') {
    return { start: fmtDate(new Date(now.getFullYear(), 0, 1)), end: fmtDate(now) }
  }
  const r = customRange.value
  return r && r.length === 2
    ? { start: r[0], end: r[1] }
    : { start: fmtDate(new Date(now.getFullYear(), now.getMonth(), 1)), end: fmtDate(now) }
})

const baseParams = computed(() => ({
  start: range.value.start,
  end: range.value.end,
  ...(accountId.value ? { accountId: accountId.value } : {}),
}))

onMounted(async () => {
  const res = await request.get('/accounts')
  accounts.value = res.data.accounts
  Object.assign(accSummary, {
    netWorth: res.data.netWorth,
    assetTotal: res.data.assetTotal,
    liabilityTotal: res.data.liabilityTotal,
    accounts: res.data.accounts,
  })
  // 下钻：点击一级扇区进入二级（UC-5.5 可选下钻）
  onPie('click', params => {
    if (drill.value) return
    const item = catItems.value.find(i => i.name === params.name)
    if (item && item.children && item.children.length) drill.value = item
  })
  renderAccountsOverview()
  await loadAll()
})

async function loadAll() {
  drill.value = null
  await Promise.all([loadSummary(), loadTrend(), loadCategory()])
}

async function loadSummary() {
  const res = await request.get('/stats/summary', { params: baseParams.value })
  Object.assign(summary, res.data)
}

async function loadTrend() {
  const res = await request.get('/stats/trend', {
    params: { ...baseParams.value, groupBy: granularity.value },
  })
  const pts = res.data.points
  setTrend({
    tooltip: { trigger: 'axis', valueFormatter: v => money(v) },
    legend: { data: ['收入', '支出', '结余'], bottom: 0 },
    grid: { left: 70, right: 20, top: 20, bottom: 46 },
    xAxis: { type: 'category', data: pts.map(p => p.label) },
    yAxis: { type: 'value' },
    series: [
      { name: '收入', type: 'line', smooth: true, data: pts.map(p => Number(p.income)) },
      { name: '支出', type: 'line', smooth: true, data: pts.map(p => Number(p.expense)) },
      { name: '结余', type: 'line', smooth: true, data: pts.map(p => Number(p.balance)) },
    ],
  })
}

async function loadCategory() {
  const res = await request.get('/stats/category', {
    params: { ...baseParams.value, type: catType.value },
  })
  catItems.value = res.data.items
  renderPie()
}

function renderPie() {
  const source = drill.value ? drill.value.children : catItems.value
  if (!source.length) return
  setPie({
    tooltip: { trigger: 'item', valueFormatter: v => money(v) },
    series: [{
      type: 'pie',
      radius: ['35%', '60%'],
      center: ['50%', '50%'],
      data: source.map(i => ({ name: i.name, value: Number(i.amount) })),
      label: { formatter: '{b}\n{d}%' },
    }],
  })
}

watch(drill, renderPie)

/** 账户总览（UC-5.6）：数据源为 GET /api/accounts（P1 已提供），当前快照不随时间筛选变化 */
function renderAccountsOverview() {
  const items = accSummary.accounts
  setBar({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: v => money(v) },
    grid: { left: 90, right: 40, top: 10, bottom: 26 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: items.map(a => a.name) },
    series: [{
      type: 'bar',
      barMaxWidth: 22,
      data: items.map(a => Number(a.balance)),
      itemStyle: { color: p => (p.value < 0 ? '#e05252' : '#4e63f2') },
      label: { show: true, position: 'right', fontSize: 11, formatter: p => money(p.value) },
    }],
  })
  setRatio({
    tooltip: { trigger: 'item', valueFormatter: v => money(v) },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['35%', '60%'],
      center: ['50%', '42%'],
      data: [
        { name: '资产', value: Number(accSummary.assetTotal), itemStyle: { color: '#4e63f2' } },
        { name: '负债', value: Number(accSummary.liabilityTotal), itemStyle: { color: '#e05252' } },
      ],
      label: { formatter: '{b}\n{d}%' },
    }],
  })
}
</script>

<style scoped>
.filter-bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.stat-card { min-height: 96px; }
.stat-label { color: #8a90a3; font-size: 13px; }
.stat-value { font-size: 26px; font-weight: 700; margin-top: 8px; }
.stat-value.income, .income { color: #2f9e6e; }
.stat-value.expense, .expense { color: #e05252; }
.chart.lg { height: 320px; }
.chart.md { height: 260px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.ratio-text { color: #8a90a3; font-size: 12px; line-height: 1.9; text-align: center; padding-bottom: 6px; }
</style>
```

- [ ] **Step 3: 构建 + 目检 + 提交**

Run: `cd frontend && npm run build`
Expected: 构建成功

dev server 目检（方式同 Task 6 Step 4）：打开 `http://localhost:5173/stats`，逐项验证：切「上月/今年/自定义」卡片与图表联动；选账户后汇总/趋势/构成联动；按月/按日切换趋势粒度；点饼图扇区下钻二级、返回一级；账户条形图负债为负红色；资产/负债占比饼图与净资产文字正确。验证完回收 node/vite 进程。

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p4): 统计报表分析页（多维筛选/趋势/构成下钻/账户总览）"
```

---

### Task 8: import 模块小修批次（P3 终审分诊遗留 a~f）

**Files:**
- Modify: `backend/src/main/java/com/balancewise/importbill/service/ImportService.java`（a：批内互重）
- Modify: `backend/src/main/java/com/balancewise/importbill/service/AccountMatcher.java`（e：拆箱防御）
- Modify: `backend/src/main/java/com/balancewise/importbill/service/CategoryMappingService.java`（f：类注释）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportConfirmTest.java`（a 用例）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportBatchTest.java`（b 用例）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportPreviewTest.java`（c 两个用例）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportMappingTest.java`（d 用例）

**Interfaces:**
- Consumes: P3 既有 ImportService.confirm/rollback/preview、ImportConfirmTest 的 `newAccount` helper。
- Produces: 六条终审分诊小修全部落地；净增 5 个用例（88 → 93）。**a 是先红后绿；b/c/d 是刻画测试，补写后即绿**（计划已标注，避免误判为异常绿灯）。

- [ ] **Step 1: (a) 写失败测试（ImportConfirmTest 追加）**

```java
    @Test
    void inBatchDuplicateExternalIdRejected() {
        Account def = newAccount("现金");
        ImportRowRequest row = new ImportRowRequest();
        row.setType("expense");
        row.setFromAccountId(def.getId());
        row.setAmount(new BigDecimal("10.00"));
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setExternalId("EXT-DUP");
        ImportRowRequest dup = new ImportRowRequest();
        dup.setType("expense");
        dup.setFromAccountId(def.getId());
        dup.setAmount(new BigDecimal("20.00"));
        dup.setOccurredAt(LocalDateTime.of(2026, 9, 2, 12, 0));
        dup.setExternalId("EXT-DUP");
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("dup.csv");
        req.setRows(java.util.List.of(row, dup));
        BizException e = assertThrows(BizException.class, () -> importService.confirm(1L, req));
        assertTrue(e.getMessage().contains("重复"));
    }
```

- [ ] **Step 2: (a) 跑测试确认红，再修 ImportService.confirm**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=ImportConfirmTest#inBatchDuplicateExternalIdRejected`
Expected: FAIL（当前实现不做批内互重，两条同 EXT-DUP 的流水会正常入账）

在 `ImportService.confirm` 中，把现有 externalIds 收集之后的判重块替换为「批内互重 → 库内判重」两段：

```java
        List<String> externalIds = req.getRows().stream()
                .map(ImportRowRequest::getExternalId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        // 批内互重校验：同一批次中 external_id 不得重复（防止同一文件内重复交易号自我重复入账）
        Set<String> seenInBatch = new HashSet<>();
        for (String id : externalIds) {
            if (!seenInBatch.add(id)) {
                throw new BizException("批次内存在重复的 external_id：" + id);
            }
        }
        long dupCount = existingExternalIds(userId, req.getPlatform(), externalIds).size();
        if (dupCount > 0) {
            throw new BizException("包含 " + dupCount + " 条已入账记录，请重新预览后再确认");
        }
```

（`HashSet`/`Set` 的 import P3 已有；重复则直接替换旧判重段。）

再跑：`-Dtest=ImportConfirmTest` Expected: PASS。

- [ ] **Step 3: (b) 撤销后重导同文件成功（ImportBatchTest 追加，刻画测试补写后即绿）**

```java
    @Test
    void reimportAfterRollbackSucceeds() {
        Long batchId = confirmedBatchWithOneExpense();
        importService.rollback(1L, batchId);

        // 撤销后流水软删，重复检测不再命中（§5.4 规则 4：只比对未删除记录），同文件可重新入账
        Account cash = accountService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, 1L).eq(Account::getName, "现金"));
        ImportRowRequest row = new ImportRowRequest();
        row.setType("expense");
        row.setFromAccountId(cash.getId());
        row.setAmount(new BigDecimal("100.00"));
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setExternalId("EXT-001");
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("t.csv");
        req.setRows(List.of(row));
        ImportBatch again = importService.confirm(1L, req);
        assertEquals("confirmed", again.getStatus());
        // 余额再次扣减 100（重导生效）
        assertEquals(0, new BigDecimal("-100.00").compareTo(
                accountService.getById(cash.getId()).getBalance()));
    }
```

- [ ] **Step 4: (c)(d) 刻画测试补写（ImportPreviewTest / ImportMappingTest 追加，即绿）**

ImportPreviewTest 追加：

```java
    @Test
    void unsupportedPlatformRejected() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        BizException e = assertThrows(BizException.class,
                () -> importService.preview(1L, "paypal", content, null));
        assertEquals("不支持的平台：paypal", e.getMessage());
    }

    @Test
    void duplicateDetectionIsolatedAcrossUsers() throws Exception {
        Account def = newAccount("现金");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        // user 1 已入账该 external_id 的未删除流水
        com.balancewise.record.entity.TransactionRecord exist = new com.balancewise.record.entity.TransactionRecord();
        exist.setUserId(1L);
        exist.setType("expense");
        exist.setFromAccountId(def.getId());
        exist.setAmount(new BigDecimal("35.50"));
        exist.setOccurredAt(java.time.LocalDateTime.of(2026, 9, 1, 8, 30, 22));
        exist.setSource("alipay");
        exist.setExternalId("202609010000100001");
        exist.setIsDeleted(0);
        transactionRecordMapper.insert(exist);
        // user 1 预览 → 标重复
        assertTrue(importService.preview(1L, "alipay", content, def.getId()).getRows().stream()
                .filter(r -> "202609010000100001".equals(r.getExternalId()))
                .findFirst().orElseThrow().isDuplicate());
        // user 2 预览同一文件 → 不重复（userId 严格隔离）
        assertFalse(importService.preview(2L, "alipay", content, null).getRows().stream()
                .filter(r -> "202609010000100001".equals(r.getExternalId()))
                .findFirst().orElseThrow().isDuplicate());
    }
```

ImportMappingTest 追加：

```java
    @Test
    void transferTypeNeverMapped() {
        category("餐饮", "expense");
        // 转账不挂分类：即使文本命中关键词也返回 null
        assertNull(categoryMappingService.mapCategoryId(1L, "transfer", "余额宝转入", "余额宝"));
        assertNull(categoryMappingService.mapCategoryId(1L, "transfer", "转账", "十月工资"));
    }
```

（c/d 均为既有行为的刻画测试，补写后即为绿灯，属预期；若红则说明实现回退，停止上报。）

- [ ] **Step 5: (e)(f) 源码小修**

AccountMatcher 两处 `== 0` 拆箱改为 `Integer.valueOf(0).equals(...)`（is_archived 为 Integer，null 时拆箱 NPE）：

```java
            if (hit != null && Integer.valueOf(0).equals(hit.getIsArchived())) {
                return hit;
            }
```

```java
            if (def != null && def.getUserId().equals(userId) && Integer.valueOf(0).equals(def.getIsArchived())) {
                return def;
            }
```

CategoryMappingService 类注释改为（去掉「先按平台分类精确表查」这一不实描述——实现只有关键词规则）：

```java
/**
 * 分类自动映射（UC-4.4）：内置关键词规则，匹配 平台分类+商品 文本；
 * 只在用户自己的分类中按名称解析；无匹配返回 null（未分类）。
 */
```

- [ ] **Step 6: 全量回归并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 93/93（88 既有 + a/b/c×2/d 共 5 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "fix(p4): import 终审小修——批内 external_id 互重/撤销后重导用例/预览平台与用户隔离/transfer映射/拆箱防御/注释修正"
```

---

### Task 9: P4 收尾——回归、e2e、README、tag

**Files:**
- Modify: `README.md`（功能清单追加统计行）

- [ ] **Step 1: 后端全量回归**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 93/93

- [ ] **Step 2: e2e（curl + 真实 MySQL，用户 tester01/123456）**

后端后台启动（powershell Start-Process，日志 `logs/backend-p4-e2e.log`；MySQL 在后台）。含中文请求体写 UTF-8 文件。

1. 登录拿 token；造数：建「工资卡」账户（余额 8000）与一笔本月支出（餐饮 35.5）、一笔上月支出（购物 120）、一笔本月收入（工资 5000）。
2. `GET /api/stats/summary?start=<本月1号>&end=<今天>` → income=5000.00、expense=35.50、balance=4964.50。
3. `GET /api/stats/trend?groupBy=month&months=6` → 6 个桶（含当月），近两个月 expense 分别 120.00/35.50，其余补零。
4. `GET /api/stats/trend?groupBy=day&start=<本月1号>&end=<今天>` → 桶数 = 天数，仅有流水日非零；`groupBy=day` 不带 start → code=400「按日统计必须提供 start 和 end」。
5. `GET /api/stats/net-worth?groupBy=month&months=3` → initialNetWorth=8000.00，各月净额逐月递减 120/35.5、收入月加 5000（断言每月值 = 上月值 + 当月(income−expense)）。
6. `GET /api/stats/category?type=expense&start=<本月1号>&end=<今天>` → items 含「餐饮」（percentage 可手算核对），上月购物不混入；`type=transfer` → code=400。
7. `GET /api/stats/summary` 不带 token → HTTP 401。
8. 账户筛选：`summary?...&accountId=<工资卡id>` 与全部口径对比，数值不超过全口径。
9. 清理：删除本次 e2e 产生的流水/账户，库终态回到 user 1 / account 1 / category 39 / txn 0 / batch 0。

- [ ] **Step 3: 前端构建**

Run: `cd frontend && npm run build`
Expected: 构建成功

- [ ] **Step 4: README 功能清单追加**

在「当前功能」列表的导出行后追加一行：

```markdown
- 统计：首页仪表盘（净资产/本月收支/近 6 月趋势/支出构成/最近流水）与分析页（汇总卡片、收支趋势、净资产趋势、分类构成下钻、账户总览、时间/账户/类型多维筛选）
```

- [ ] **Step 5: 提交 + 打 tag + 回收进程**

```bash
git add README.md
git -c user.name="李东升" -c user.email="lids@local" commit -m "docs(p4): README 功能清单更新"
git tag p4-stats-v1
```

确认 tag 与分支 `p4-stats` 不同名无歧义；回收后端进程（不杀 MySQL）。

- [ ] **Step 6: 汇报**

报告写 `.superpowers/sdd/task-9-report.md`（回归数、e2e 各端点实测值、库终态、tag）。向所有者汇报，等待阶段评审。

---

## Self-Review 记录

**1. 规格覆盖：**
- UC-5.1 首页仪表盘 → Task 6（净资产卡片/本月收支卡片/近 6 月迷你趋势/本月支出饼图/最近 10 条流水，ECharts）。
- UC-5.2 汇总卡片 → Task 1（summary 服务）+ Task 5（端点）+ Task 6/7（卡片）。
- UC-5.3 收支趋势（月近 6/12、日自定义、收入/支出/结余三线）→ Task 2 + Task 5 + Task 7（按月/按日切换）。
- UC-5.4 净资产趋势 → Task 3（口径 = 初始余额（负债取负）+ 流水累计，规格 §5.4 规则 2/3 与 §7 对账流水可追溯性一致；含归档账户）+ Task 7 可视化。
- UC-5.5 分类构成（支出/收入可选、一级聚合、时间筛选、点击下钻二级、未分类单列）→ Task 4 + Task 7（扇区点击下钻）。
- UC-5.6 账户总览（余额对比条形图 + 资产/负债占比）→ 并入 Task 7：数据复用 P1 `GET /api/accounts`（AccountSummaryVo 已含 accounts/netWorth/assetTotal/liabilityTotal），无新增后端端点；合并理由已写入 Task 7 头部。
- UC-5.7 多维筛选（时间/账户/类型）→ 时间预设（本月/上月/今年/自定义）+ 账户筛选（summary/trend/category 均带 accountId）+ 类型切换（构成图支出/收入 radio；趋势固定三线展示收支与结余）。
- §6 统计节四个端点 → Task 5，路径与规格一致（/api/stats/summary|trend|net-worth|category）。
- §5 表结构与 §5.4 规则：统计只查 is_deleted=0（规则 4 软删语义）；转账不进收支（规则 2 双向抵消）；净资产 = SUM 口径的重构式表达（规则 3）。
- §9 测试策略：白盒（Task 1~4 服务单测、Task 8 import 用例）+ 黑盒 MockMvc（Task 5）+ 前端手工用例（Task 6/7 目检清单、Task 9 e2e）。
- P3 终审分诊小修 a~f → Task 8 逐条落地，每条带代码/用例。
- 无规格缺口。「近 6/12 个月」的 12 由 months 参数支持（≤24），前端仪表盘固定 6、分析页走区间，满足 UC-5.3 两种月数。

**2. 占位符扫描：** 全文无 TBD/TODO/「类似 Task N」；每个代码步骤给出完整可编译代码；测试步骤含完整断言。两处「先红后绿」已显式标注（Task 8-a 红灯预期；b/c/d 刻画测试绿灯预期）。Task 6/7 的目检步骤给出逐项验证清单而非占位。

**3. 类型一致性：**
- VO 字段跨任务一致：`StatsSummaryVo{income,expense,balance}`、`TrendPointVo{label,income,expense,balance}`、`NetWorthPointVo{label,netWorth}`、`CategoryStatItemVo{categoryId,name,amount,percentage,children}`（未分类 categoryId=null）、`StatsCategoryVo{type,total,items}`——Task 4 定义与 Task 5 controller 泛型、Task 6/7 前端 `res.data.*` 消费一致。
- 端点参数一致：Task 5 的 `@RequestParam`/`StatsTrendQuery`/`StatsCategoryQuery` 与 Task 6/7 的 axios params（groupBy/months/start/end/accountId/type）一致；`@DateTimeFormat(iso = ISO.DATE)` 与前端 `YYYY-MM-DD` value-format 一致。
- Service 签名：Task 1~4 的四个 public 方法与 Task 5 调用一致；私有助手 `resolveRange/Range/bucketLabels/bucketEndDate/fromOf/toOf` 在 Task 2 定义、Task 3/4 复用，命名全文统一。
- Mapper 签名：Interfaces 总表与 Task 1/2/4 实现一致（sumByType/dailyFlows/sumByCategory，from 可空、to 排他、accountId 可空）。
- `useChart(): {el, setOption, on}` Task 6 定义、Task 7 复用，签名一致。
- 测试基线计数逐任务递增（71→74→78→81→84→88→93），每任务 Expected 与新增用例数核对一致。

**4. 账务安全声明：** 统计模块全部只读，Global Constraints 明令禁止触碰 `account.balance`/`applyBalance`；Task 3 净资产为纯 SQL/Java 重构计算，不产生任何写路径；import 小修（Task 8）只在 confirm 前置校验加互重，不改动余额联动。

**5. 已知计划内简化：** 账户总览为当前快照（不随时间筛选，与 UC-2.5 口径一致）；net-worth 忽略 accountId（净资产是全账户口径）；趋势按日上限 366 天、按月上限 24 个月（性能护栏，单用户 10 万级流水下聚合 SQL 走 `idx_txn_user_time`）。
