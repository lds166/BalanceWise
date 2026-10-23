# P5 收尾阶段实施计划（对账 / 用户中心 / 体验打磨 / Minor 清算）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 UC-2.6 余额校正（对账）全链路（后端 `POST /api/accounts/{id}/reconcile` + 账户页对账入口）、UC-1.5/UC-1.6 用户中心页（改密/改昵称），并清偿 P1~P4 终审分诊遗留的 Minor 台账（后端 3 项实修 + 3 项补测试 + 7 项核实已修，前端 6 项），最后 e2e 验收、README 更新、打 tag `p5-polish-v1`。

**Architecture:** 对账不新增任何余额路径——`ReconcileService` 计算差额（负债账户按"界面正数欠款、库内负数"口径归一）后，直接复用 `TransactionService.importRecord(userId, "reconcile", null, null, req)` 生成校正流水（差额>0 记 income、<0 记 expense，分类归入「其他支出/其他收入」，备注"余额校正"），余额联动、校验、事务全部由既有单一抽象承载。用户中心后端接口（`PUT /api/user/password`、`PUT /api/user/nickname`）P1 已存在且签名核实无误，本阶段只做前端页面。Minor 修复遵循最小改动：参数校验补 400、解析加固、TOCTOU 改条件更新，其余已修项只做回归确认。

**Tech Stack:** Spring Boot 3.3 + MyBatis-Plus + JUnit 5 + MockMvc（H2 MySQL 模式）；Vue 3 + Element Plus + ECharts 5.6（不新增依赖）。

## Global Constraints

- JDK 17：`C:\Program Files\Java\jdk-17`（每个 Bash 调用重新 `export JAVA_HOME` 与 PATH）
- Maven：`D:\KimiData\kimi\Workspaces\BalanceWise\.tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\KimiData\kimi\Workspaces\BalanceWise\.tools\maven-settings.xml -f backend/pom.xml`
- git 提交身份：`-c user.name="李东升" -c user.email="lids@local"`
- 中文源码与文档一律 UTF-8（含 Java/Vue/JS 源文件、Markdown、curl 请求体文件）
- 统一返回 `Result{code,message,data}`；code=0 成功、400 业务错误、401 仅 JWT 未登录（HTTP 状态也只有 401 走非 200 与静态资源 404，其余业务失败一律 HTTP 200 + code=400）
- **余额规则**：绝不新增余额变动路径。校正流水必须复用既有 `TransactionService.importRecord`（内部即 `createInternal` + `applyBalance`），禁止复制/改造余额 SQL
- **统计只读约束继续**：本阶段统计模块零改动（仅补测试）
- 金额口径：全部 `BigDecimal`，`setScale(2, RoundingMode.HALF_UP)`；比较用 `compareTo`
- userId 隔离：所有数据操作校验资源归属当前用户；测试造数如需他人数据用 userId=2L
- 测试约定：`@SpringBootTest @ActiveProfiles("test")` + 类级 `@Transactional`（MockMvc 测试同样加 `@Transactional`，沿用 TransactionApiTest 模式）；H2 内存库（schema 由 `src/main/resources/schema.sql` 初始化）；**基线 93 个用例（main @ d0b52f9）**
- Git Bash 内嵌中文 JSON 会 GBK 乱码：含中文请求体写 UTF-8 文件 `curl -d @文件`
- Bash 不支持后台任务：后台进程用 `powershell Start-Process` 分离，验证完回收
- 阶段分支：`p5-polish`（从 main @ d0b52f9 切出）；阶段 tag：`p5-polish-v1`
- 前端约束：不新增 npm 依赖；请求统一走 `src/api/request.js`；ECharts 仍由 `useChart.js` 全量 `import * as echarts from 'echarts'`（打包优化走 vite manualChunks，不改 import 语义）
- 前端路由全部挂在 MainLayout children 下；新增页面必须同时加 `router/index.js` 路由与 `MainLayout.vue` 菜单项

---

## P5 Minor 台账核对结论（执行前必读，决定哪些项只回归不写码）

以下台账项经源码核实**已在前期修复，本阶段不做代码修改，只需全量回归确认**（无任何动作，回归绿即视为清偿）：

| # | 台账项 | 现状核实 |
|---|---|---|
| 2 | end 边界亚秒缝隙 | **已修**：`TransactionService.buildWrapper:244` 已是 `end.plusDays(1).atStartOfDay()` 排他上界（P3 commit 51443a5），与 P4 stats 口径一致；既有测试 `endDateFilterIncludesWholeDayExclusively` 锁定 |
| 3 | csv() 未检查孤立 \r | **已修**：`csv():316` 已含 `s.contains("\r")` 引号包裹分支；既有测试 `exportCsvGuardsFormulaInjectionAndCarriageReturn` 锁定 |
| 4a | 导出 50001 上限判定 | **已修**：`exportCsv()` 已是 `LIMIT 50001` + `size() > 50000` 拒绝（恰好 50000 成功） |
| 4c | 导出响应头设置 | **已修**：`TransactionController.export:54` 已显式 `Content-Type: text/csv;charset=UTF-8` + `Content-Disposition: attachment`；仅测试缺断言（见 Task 4-③） |
| 6 | DuplicateKey 友好化 | **已修**：`GlobalExceptionHandler.handleDuplicateKey` 已返回 code=400 |
| 7 | printStackTrace | **已清**：全后端 grep 零命中 |
| 8 | JWT 配置外置 | **已修**：`application.yml` 与 `application-test.yml` 均有 `jwt.secret`/`jwt.expire-hours`，`JwtUtil` 以 `@Value` 注入 |

以下台账项确认**仍然缺失/待修**，按任务划分实施：

- 后端：#1 categories 裸 GET 500→400、#5 to 侧筛选测试、#9 parseAmount 加固、#10 rollback TOCTOU 条件更新、#11 月桶最后一日测试、#4b 导出响应头断言（Task 4）
- 前端：#13 时间本地化、#14 unhandled rejection 兜底（方案定死在 Task 5-①：main.js 全局 `unhandledrejection` 监听 + `preventDefault`，因 `request.js` 拦截器已统一弹错误消息，页面不再逐个加 .catch）、#15 删 PlaceholderView、#16 vite manualChunks、#17 StatsView 死代码、#18 AccountRow emits 声明（并入 Task 2 对账按钮一起做，避免同一组件两次改动）、#19 用户中心页（Task 3）、#20 对账前端入口（Task 2）

---

## 文件结构（本阶段新增/修改清单）

**后端新增：**

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/balancewise/account/dto/ReconcileRequest.java` | 对账请求体 `{actualBalance}`（@NotNull） |
| `backend/src/main/java/com/balancewise/account/vo/ReconcileResultVo.java` | 对账结果：before/after/diff/校正流水 id（库内口径） |
| `backend/src/main/java/com/balancewise/account/service/ReconcileService.java` | 差额计算 + 归一 + 复用 importRecord 生成分校流水 |
| `backend/src/test/java/com/balancewise/account/ReconcileServiceTest.java` | 对账服务层测试（7 用例） |
| `backend/src/test/java/com/balancewise/account/ReconcileApiTest.java` | 对账 MockMvc 测试（4 用例） |
| `backend/src/test/java/com/balancewise/category/CategoryApiTest.java` | 分类端点参数校验 MockMvc 测试（2 用例） |
| `backend/src/test/java/com/balancewise/importbill/BillParserSupportTest.java` | parseAmount 千分位加固测试（2 用例） |

**后端修改：**

| 文件 | 修改 |
|---|---|
| `account/controller/AccountController.java` | 追加 `POST /{id}/reconcile` 端点 |
| `category/controller/CategoryController.java:26-28` | `kind` 改 `required=false` + 显式 400 校验 |
| `common/GlobalExceptionHandler.java` | 追加 `MissingServletRequestParameterException` → 400 兜底 |
| `importbill/parser/BillParserSupport.java:85-90` | `parseAmount` 千分位模式加固，畸形 "1,2" 拒绝 |
| `importbill/service/ImportService.java:204-223` | `rollback` 先验 status 改条件更新抢占（TOCTOU） |
| `record/test/.../TransactionServiceTest.java` | 追加 to 侧筛选用例 1 条 |
| `record/test/.../TransactionApiTest.java` | `exportReturnsCsv` 追加响应头断言 |
| `importbill/test/.../ImportBatchTest.java` | 追加 TOCTOU 守护用例 1 条 |
| `stats/test/.../StatsServiceTest.java` | 追加月桶最后一日用例 1 条 |

**前端新增/修改：**

| 文件 | 说明 |
|---|---|
| `frontend/src/views/UserCenterView.vue` | 新建：改昵称 + 改密（Task 3） |
| `frontend/src/views/AccountView.vue` | 对账按钮 + 对账弹窗 + AccountRow emits 声明（Task 2） |
| `frontend/src/views/DashboardView.vue` | 最近流水时间列本地化格式化（Task 5-②） |
| `frontend/src/router/index.js` | 加 `/user` 路由 |
| `frontend/src/layouts/MainLayout.vue` | 菜单加「用户中心」 |
| `frontend/src/main.js` | 全局 unhandledrejection 兜底（Task 5-①） |
| `frontend/vite.config.js` | manualChunks 拆分 echarts/element-plus（Task 5-④） |
| `frontend/src/views/StatsView.vue` | 删 lastMonth 分支死代码 `first` 变量（Task 5-⑤） |
| `frontend/src/views/PlaceholderView.vue` | 删除（已确认全仓库零引用，Task 5-③） |
| `README.md` | 功能清单补对账与用户中心（Task 6） |

---

## 跨任务接口契约（Interfaces 总表）

**REST 端点（Task 1 产出，Task 2 消费；全部需 `Authorization: Bearer <token>`）：**

| 方法 | 路径 | 请求体 | 返回 data |
|---|---|---|---|
| POST | /api/accounts/{id}/reconcile | `{"actualBalance": "150.00"}`（负债账户按界面正数欠款录入） | `ReconcileResultVo{accountId, accountName, beforeBalance, afterBalance, diff, correctionType, transactionId}`，全部库内口径（负债为负），`diff > 0` 表示记收入校正 |

**既有端点（Task 3 消费，签名已核实）：**

```js
request.put('/user/nickname', { nickname })   // → Result<Void>，昵称 @NotBlank @Size(max=32)
request.put('/user/password', { oldPassword, newPassword })  // → Result<Void>，newPassword @Size(min=6,max=64)
```

**Service 签名（Task 1 产出）：**

```java
ReconcileResultVo reconcile(Long userId, Long accountId, ReconcileRequest req)
// 抛出 BizException：账户不存在（越权同文案）/ 已归档请先恢复 / 无需校正：账户余额与实际一致
```

**复用接口（本阶段不改动，Task 1 直接调用）：**

```java
TransactionRecord importRecord(Long userId, String source, String externalId, Long importBatchId, TransactionRequest req)
// source 传 "reconcile"，externalId/importBatchId 传 null；source 标签 "reconcile"→"余额校正" 已存在于 TransactionService.SOURCE_LABEL
```

**金额口径约定（Task 1 服务端内部，Task 2 前端展示必须同规则）：**

- 库内口径：`balance` 负债为负；`diff = 归一后 actualBalance − balance`（资产归一=原值，负债归一=实际录入值取负）；`diff > 0` → income（to=该账户），`diff < 0` → expense（from=该账户），amount=|diff|
- 展示口径（仅前端弹窗预览用）：`账面余额` = 负债时 `-balance`，`差额预览` = 录入值 − 账面余额（正数=将增加余额/减少欠款）；提交后以后端返回为准

---

### Task 1: 对账后端（ReconcileService + 端点 + 测试）

**Files:**
- Create: `backend/src/main/java/com/balancewise/account/dto/ReconcileRequest.java`
- Create: `backend/src/main/java/com/balancewise/account/vo/ReconcileResultVo.java`
- Create: `backend/src/main/java/com/balancewise/account/service/ReconcileService.java`
- Modify: `backend/src/main/java/com/balancewise/account/controller/AccountController.java`（追加 reconcile 端点）
- Test: `backend/src/test/java/com/balancewise/account/ReconcileServiceTest.java`（新建，7 用例）
- Test: `backend/src/test/java/com/balancewise/account/ReconcileApiTest.java`（新建，4 用例）

**Interfaces:**
- Consumes: 既有 `AccountService`（测试造数/归档）、`AccountMapper`、`TransactionService.importRecord`、`TransactionRecordMapper`（断言流水）、`CategoryMapper`（造「其他支出」分类）、`Result`/`BizException`。
- Produces: `ReconcileService.reconcile(Long, Long, ReconcileRequest): ReconcileResultVo`；`ReconcileRequest{BigDecimal actualBalance}`；`ReconcileResultVo{Long accountId, String accountName, BigDecimal beforeBalance, BigDecimal afterBalance, BigDecimal diff, String correctionType, Long transactionId}`（correctionType ∈ income/expense）。Task 2 前端按上述 REST 契约消费。

- [ ] **Step 1: 建分支**

```bash
cd D:\KimiData\kimi\Workspaces\BalanceWise
git checkout main && git pull --ff-only 2>/dev/null; git checkout -b p5-polish
git log --oneline -1   # 确认 d0b52f9
```

- [ ] **Step 2: 写失败测试（ReconcileServiceTest，新建）**

```java
package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.dto.ReconcileRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.account.service.ReconcileService;
import com.balancewise.account.vo.ReconcileResultVo;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReconcileServiceTest {

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionRecordMapper transactionRecordMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    private Account acc(String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private ReconcileRequest req(String actual) {
        ReconcileRequest r = new ReconcileRequest();
        r.setActualBalance(new BigDecimal(actual));
        return r;
    }

    private long reconcileTxnCount() {
        return transactionRecordMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                .eq(TransactionRecord::getUserId, 1L)
                .eq(TransactionRecord::getSource, "reconcile"));
    }

    @Test
    void positiveDiffCreatesIncomeCorrection() {
        Account a = acc("对账卡A", "asset", "debit_card", "100.00");
        ReconcileResultVo vo = reconcileService.reconcile(1L, a.getId(), req("150.00"));

        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getBeforeBalance()));
        assertEquals(0, new BigDecimal("150.00").compareTo(vo.getAfterBalance()));
        assertEquals(0, new BigDecimal("50.00").compareTo(vo.getDiff()));
        assertEquals("income", vo.getCorrectionType());
        assertNotNull(vo.getTransactionId());
        // 余额经既有 importRecord 路径联动
        assertEquals(0, new BigDecimal("150.00").compareTo(accountService.getById(a.getId()).getBalance()));
        // 校正流水可追溯：source=reconcile、备注"余额校正"
        TransactionRecord r = transactionRecordMapper.selectById(vo.getTransactionId());
        assertEquals("reconcile", r.getSource());
        assertEquals("income", r.getType());
        assertEquals(a.getId(), r.getToAccountId());
        assertNull(r.getFromAccountId());
        assertEquals("余额校正", r.getItem());
        assertEquals(0, new BigDecimal("50.00").compareTo(r.getAmount()));
    }

    @Test
    void negativeDiffCreatesExpenseCorrection() {
        Account a = acc("对账卡B", "asset", "debit_card", "100.00");
        ReconcileResultVo vo = reconcileService.reconcile(1L, a.getId(), req("80.00"));

        assertEquals("expense", vo.getCorrectionType());
        assertEquals(0, new BigDecimal("-20.00").compareTo(vo.getDiff()));
        assertEquals(0, new BigDecimal("80.00").compareTo(vo.getAfterBalance()));
        TransactionRecord r = transactionRecordMapper.selectById(vo.getTransactionId());
        assertEquals(a.getId(), r.getFromAccountId());
        assertNull(r.getToAccountId());
    }

    @Test
    void liabilityAccountUsesDisplaySign() {
        // 负债账户：界面按正数录入欠款 400，库内 balance = -400；账面 -500 → 差额 +100 记收入
        Account c = acc("对账信用C", "liability", "credit_card", "500.00");
        assertEquals(0, new BigDecimal("-500.00").compareTo(accountService.getById(c.getId()).getBalance()));

        ReconcileResultVo vo = reconcileService.reconcile(1L, c.getId(), req("400.00"));
        assertEquals("income", vo.getCorrectionType());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getDiff()));
        assertEquals(0, new BigDecimal("-400.00").compareTo(vo.getAfterBalance()));
    }

    @Test
    void zeroDiffRejectedWithoutTransaction() {
        Account a = acc("对账卡D", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> reconcileService.reconcile(1L, a.getId(), req("100.00")));
        assertTrue(e.getMessage().contains("无需校正"));
        assertEquals(0, reconcileTxnCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(accountService.getById(a.getId()).getBalance()));
    }

    @Test
    void otherUserAccountRejected() {
        Account a = acc("对账卡E", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> reconcileService.reconcile(2L, a.getId(), req("150.00")));
        assertEquals("账户不存在", e.getMessage());
        assertEquals(0, reconcileTxnCount());
    }

    @Test
    void archivedAccountRejected() {
        Account a = acc("对账卡F", "asset", "debit_card", "100.00");
        accountService.archive(1L, a.getId(), true);
        BizException e = assertThrows(BizException.class,
                () -> reconcileService.reconcile(1L, a.getId(), req("150.00")));
        assertTrue(e.getMessage().contains("已归档"));
        assertEquals(0, reconcileTxnCount());
    }

    @Test
    void correctionUsesOtherCategoryWhenPresent() {
        Category other = new Category();
        other.setUserId(1L);
        other.setName("其他支出");
        other.setKind("expense");
        other.setIsSystem(0);
        categoryMapper.insert(other);

        Account a = acc("对账卡G", "asset", "debit_card", "100.00");
        ReconcileResultVo vo = reconcileService.reconcile(1L, a.getId(), req("70.00"));
        TransactionRecord r = transactionRecordMapper.selectById(vo.getTransactionId());
        assertEquals(other.getId(), r.getCategoryId());
    }
}
```

- [ ] **Step 3: 写失败测试（ReconcileApiTest，新建）**

```java
package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReconcileApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long prepareAccount(String name, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("debit_card");
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(1L, r).getId();
    }

    @Test
    void reconcileSuccessReturnsVo() throws Exception {
        String token = jwtUtil.generate(1L);
        Long id = prepareAccount("接口对账卡", "200.00");
        MvcResult res = mockMvc.perform(post("/api/accounts/" + id + "/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualBalance\":\"260.50\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
        assertEquals(id, data.path("accountId").asLong());
        assertEquals("income", data.path("correctionType").asText());
        assertEquals(0, new BigDecimal("60.50")
                .compareTo(new BigDecimal(data.path("diff").asText())));
        assertTrue(data.path("transactionId").asLong() > 0);
    }

    @Test
    void noTokenReturns401() throws Exception {
        Long id = prepareAccount("接口对账卡2", "0.00");
        mockMvc.perform(post("/api/accounts/" + id + "/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualBalance\":\"1.00\"}"))
                .andExpect(status().is(401));
    }

    @Test
    void missingActualBalanceReturns400() throws Exception {
        String token = jwtUtil.generate(1L);
        Long id = prepareAccount("接口对账卡3", "0.00");
        MvcResult res = mockMvc.perform(post("/api/accounts/" + id + "/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void notFoundAccountReturns400() throws Exception {
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(post("/api/accounts/999999999/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualBalance\":\"1.00\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }
}
```

- [ ] **Step 4: 跑测试确认红**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-17" && export PATH="$JAVA_HOME/bin:$PATH" && .tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=ReconcileServiceTest,ReconcileApiTest`
Expected: 编译失败（ReconcileService/ReconcileRequest/ReconcileResultVo 不存在）= 红灯

- [ ] **Step 5: 实现三个新建类 + AccountController 追加端点**

`ReconcileRequest.java`：

```java
package com.balancewise.account.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 余额校正（UC-2.6 / 规格 7.6）：录入账户实际余额（负债账户按界面正数欠款录入），由后端归一为库内口径 */
@Data
public class ReconcileRequest {
    @NotNull(message = "请填写实际余额")
    private BigDecimal actualBalance;
}
```

`ReconcileResultVo.java`：

```java
package com.balancewise.account.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 余额校正结果（UC-2.6）。全部库内口径（负债账户为负）：
 * diff = 归一后实际余额 − 校正前余额；diff > 0 记收入校正，diff < 0 记支出校正。
 */
@Data
public class ReconcileResultVo {
    private Long accountId;
    private String accountName;
    private BigDecimal beforeBalance;
    private BigDecimal afterBalance;
    private BigDecimal diff;
    private String correctionType;
    private Long transactionId;
}
```

`ReconcileService.java`（**不新增任何余额路径**：差额计算后全部委托 `TransactionService.importRecord`）：

```java
package com.balancewise.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.dto.ReconcileRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.account.vo.ReconcileResultVo;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 余额校正（UC-2.6 / 规格 7.6）：差额 = 实际余额 − 当前余额，为 0 拒绝；
 * 差额 > 0 记收入（to=该账户）、< 0 记支出（from=该账户），分类归入「其他」，备注"余额校正"，source=reconcile。
 * 校正流水复用 TransactionService.importRecord 既有路径（校验/余额联动/事务零新增），可查询、可编辑、可删除冲销。
 */
@Service
public class ReconcileService {

    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionService transactionService;

    public ReconcileService(AccountMapper accountMapper, CategoryMapper categoryMapper,
                            TransactionService transactionService) {
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
        this.transactionService = transactionService;
    }

    @Transactional
    public ReconcileResultVo reconcile(Long userId, Long accountId, ReconcileRequest req) {
        Account a = accountMapper.selectById(accountId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BizException("账户不存在");
        }
        if (a.getIsArchived() == 1) {
            throw new BizException("账户「" + a.getName() + "」已归档，请先恢复后再对账");
        }

        // 口径归一：负债账户界面按正数欠款录入，库内余额为负（规格 5.4 规则 2）
        BigDecimal actualStored = req.getActualBalance().setScale(2, RoundingMode.HALF_UP);
        if ("liability".equals(a.getType())) {
            actualStored = actualStored.negate();
        }
        BigDecimal before = a.getBalance();
        BigDecimal diff = actualStored.subtract(before);
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("无需校正：账户余额与实际一致");
        }

        boolean positive = diff.compareTo(BigDecimal.ZERO) > 0;
        TransactionRequest txn = new TransactionRequest();
        txn.setType(positive ? "income" : "expense");
        txn.setToAccountId(positive ? accountId : null);
        txn.setFromAccountId(positive ? null : accountId);
        txn.setAmount(diff.abs());
        txn.setCategoryId(findOtherCategoryId(userId, positive ? "income" : "expense"));
        txn.setOccurredAt(LocalDateTime.now());
        txn.setItem("余额校正");
        // 复用导入入账同款路径：source=reconcile，余额联动与事务由 createInternal 单一抽象承载
        TransactionRecord r = transactionService.importRecord(userId, "reconcile", null, null, txn);

        ReconcileResultVo vo = new ReconcileResultVo();
        vo.setAccountId(a.getId());
        vo.setAccountName(a.getName());
        vo.setBeforeBalance(before);
        vo.setAfterBalance(accountMapper.selectById(accountId).getBalance());
        vo.setDiff(diff);
        vo.setCorrectionType(positive ? "income" : "expense");
        vo.setTransactionId(r.getId());
        return vo;
    }

    /** 「其他支出/其他收入」一级分类（预置分类，用户可能改名故找不到时归未分类，不阻断对账） */
    private Long findOtherCategoryId(Long userId, String kind) {
        String name = "income".equals(kind) ? "其他收入" : "其他支出";
        Category c = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .isNull(Category::getParentId)
                .eq(Category::getName, name)
                .last("LIMIT 1"));
        return c != null ? c.getId() : null;
    }
}
```

`AccountController.java` 追加（构造器同步注入 ReconcileService）：

```java
    private final AccountService accountService;
    private final ReconcileService reconcileService;

    public AccountController(AccountService accountService, ReconcileService reconcileService) {
        this.accountService = accountService;
        this.reconcileService = reconcileService;
    }

    /** 余额校正（UC-2.6 / 规格 7.6）：提交实际余额，生成差额校正流水（source=reconcile） */
    @PostMapping("/{id}/reconcile")
    public Result<ReconcileResultVo> reconcile(@CurrentUser Long userId, @PathVariable Long id,
                                               @Valid @RequestBody ReconcileRequest req) {
        return Result.ok(reconcileService.reconcile(userId, id, req));
    }
```

（文件头追加 import：`com.balancewise.account.dto.ReconcileRequest`、`com.balancewise.account.service.ReconcileService`、`com.balancewise.account.vo.ReconcileResultVo`。）

- [ ] **Step 6: 跑测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 104/104（93 既有 + 11 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p5): 余额校正对账后端（差额校正流水复用 importRecord 路径，source=reconcile）"
```

---

### Task 2: 对账前端（账户页入口 + 弹窗）

**Files:**
- Modify: `frontend/src/views/AccountView.vue`（AccountRow 加对账按钮 + emits 声明；新增对账弹窗与逻辑）
- Test: 手工浏览器验证（Task 6 统一 e2e 目检）

**Interfaces:**
- Consumes: Task 1 的 `POST /api/accounts/{id}/reconcile` → `data{afterBalance, diff, correctionType, transactionId}`；既有 `GET /api/accounts`（`AccountSummaryVo`，含 `accounts[].balance` 库内口径）。
- Produces: 无后端契约新增；Task 6 e2e 目检点。

- [ ] **Step 1: AccountRow 加对账按钮并声明 emits（#18 顺手清偿）**

替换 `AccountView.vue` 中 AccountRow 定义（`<script setup>` 内）为：

```js
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
```

两处 `<AccountRow ...>` 模板标签追加 `@reconcile="openReconcile"`：

```html
      <AccountRow v-for="a in assets" :key="a.id" :acc="a"
                  @reconcile="openReconcile" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
```

```html
      <AccountRow v-for="a in liabilities" :key="a.id" :acc="a"
                  @reconcile="openReconcile" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
```

- [ ] **Step 2: 新增对账弹窗模板（紧跟账户编辑弹窗 `</el-dialog>` 之后）**

```html
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
```

- [ ] **Step 3: 新增对账逻辑（`<script setup>` 内，`remove` 函数之后）**

```js
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
```

（`<script setup>` 文件头 `import { computed, h, onMounted, reactive } from 'vue'` 已含 computed，无需改 import。）

- [ ] **Step 4: 追加弹窗样式（`<style scoped>` 末尾）**

```css
.recon-balance { font-weight: 600; font-variant-numeric: tabular-nums; }
.income { color: #3aa97a; }
.muted { color: #8a90a3; }
```

- [ ] **Step 5: 构建验证并提交**

Run: `cd frontend && npm run build 2>&1 | tail -5`
Expected: 构建成功（vite build 无 ERROR）

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p5): 账户页对账入口（对账弹窗录入实际余额，预览差额，生成校正流水）"
```

---

### Task 3: 用户中心前端（改密 / 改昵称）

**Files:**
- Create: `frontend/src/views/UserCenterView.vue`
- Modify: `frontend/src/router/index.js`（children 加 `/user`）
- Modify: `frontend/src/layouts/MainLayout.vue`（菜单加「用户中心」）
- Test: 手工浏览器验证（Task 6 统一 e2e 目检）

**Interfaces:**
- Consumes: 既有 `PUT /api/user/nickname`（body `{nickname}`，`@NotBlank @Size(max=32)`）、`PUT /api/user/password`（body `{oldPassword,newPassword}`，`newPassword @Size(min=6,max=64)`）——签名已核实（`UserController.java:21-33`）；`useUserStore`（`user` state、`logout()` action）。
- Produces: 路由 `/user`（meta.title「用户中心」）；改昵称成功后 `store.user.nickname` 与 `localStorage.bw_user` 已同步，MainLayout 头部昵称即时刷新。

- [ ] **Step 1: 新建 UserCenterView.vue**

```vue
<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="hover">
          <template #header><span>个人资料</span></template>
          <el-form label-width="80px" style="max-width: 360px">
            <el-form-item label="用户名">
              <span>{{ store.user?.username }}</span>
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="nickForm.nickname" maxlength="32" placeholder="选填，默认同用户名" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="nickForm.saving" @click="saveNickname">保存昵称</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="hover">
          <template #header><span>修改密码</span></template>
          <el-form label-width="90px" style="max-width: 420px">
            <el-form-item label="原密码">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="6-64 位" />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwdForm.confirm" type="password" show-password />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="pwdForm.saving" @click="changePassword">修改密码</el-button>
              <span class="hint" style="margin-left: 12px">修改成功后需重新登录</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()

const nickForm = reactive({ nickname: store.user?.nickname || '', saving: false })
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '', saving: false })

async function saveNickname() {
  if (!nickForm.nickname || !nickForm.nickname.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }
  nickForm.saving = true
  try {
    await request.put('/user/nickname', { nickname: nickForm.nickname.trim() })
    // 同步 Pinia 与 localStorage，MainLayout 头部昵称即时刷新
    store.user = { ...store.user, nickname: nickForm.nickname.trim() }
    localStorage.setItem('bw_user', JSON.stringify(store.user))
    ElMessage.success('昵称已更新')
  } finally {
    nickForm.saving = false
  }
}

async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (pwdForm.newPassword.length < 6 || pwdForm.newPassword.length > 64) {
    ElMessage.warning('新密码长度须为 6-64 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdForm.saving = true
  try {
    await request.put('/user/password', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    })
    ElMessage.success('密码已修改，请重新登录')
    store.logout()
    router.push('/login')
  } finally {
    pwdForm.saving = false
  }
}
</script>

<style scoped>
.hint { font-size: 12px; color: #8a90a3; }
</style>
```

- [ ] **Step 2: 注册路由（`router/index.js` children 末尾，`stats` 行之后）**

```js
      { path: 'user', component: () => import('../views/UserCenterView.vue'), meta: { title: '用户中心' } },
```

- [ ] **Step 3: MainLayout 菜单加项（「统计报表」菜单项之后）**

```html
        <el-menu-item index="/user"><el-icon><User /></el-icon>用户中心</el-menu-item>
```

`<script setup>` 的图标 import 行改为：

```js
import {
  HomeFilled, EditPen, Notebook, Download, CreditCard, CollectionTag, DataAnalysis, User,
} from '@element-plus/icons-vue'
```

- [ ] **Step 4: 构建验证并提交**

Run: `cd frontend && npm run build 2>&1 | tail -5`
Expected: 构建成功

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p5): 用户中心页（改昵称/改密，改密后强制重登）"
```

---

### Task 4: 后端 Minor 清算批次（3 项实修 + 3 项补测试，+7 测试）

**Files:**
- Modify: `backend/src/main/java/com/balancewise/category/controller/CategoryController.java:26-28`
- Modify: `backend/src/main/java/com/balancewise/common/GlobalExceptionHandler.java`（追加缺参兜底）
- Modify: `backend/src/main/java/com/balancewise/importbill/parser/BillParserSupport.java:85-90`
- Modify: `backend/src/main/java/com/balancewise/importbill/service/ImportService.java:204-223`
- Test: `backend/src/test/java/com/balancewise/category/CategoryApiTest.java`（新建，2 用例）
- Test: `backend/src/test/java/com/balancewise/importbill/BillParserSupportTest.java`（新建，2 用例）
- Test: `backend/src/test/java/com/balancewise/record/TransactionServiceTest.java`（追加 1 用例）
- Test: `backend/src/test/java/com/balancewise/record/TransactionApiTest.java`（`exportReturnsCsv` 追加断言）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportBatchTest.java`（追加 1 用例）
- Test: `backend/src/test/java/com/balancewise/stats/StatsServiceTest.java`（追加 1 用例）

**Interfaces:**
- Consumes: 既有 `TransactionService.page/exportCsv`、`ImportService.rollback/confirm`、`StatsService.trend`、`BillParserSupport.parseAmount`、MockMvc + `JwtUtil` 造 token。
- Produces: 无新对外契约；行为变更清单：① `/api/categories` 缺 kind/非法 kind → code=400（原 500）；② `parseAmount("1,2")` 抛 `IllegalArgumentException`（原静默得 12）；③ `rollback` 并发双撤销由条件更新抢占拒绝（行为不变，机制加固）；④ export 响应头（`TransactionController:54` 既有 `text/csv;charset=UTF-8`）本任务仅补测试断言，无代码改动。

- [ ] **Step 1: 写失败/守护测试（6 处：2 新建 + 4 追加）**

`CategoryApiTest.java`（新建）：

```java
package com.balancewise.category;

import com.balancewise.auth.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CategoryApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void missingKindReturns400() throws Exception {
        // 台账 #1：裸 GET 曾因 @RequestParam 缺参落入全局 500，应为 400
        MvcResult res = mockMvc.perform(get("/api/categories")
                        .header("Authorization", "Bearer " + jwtUtil.generate(1L)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void invalidKindReturns400() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/categories")
                        .param("kind", "profit")
                        .header("Authorization", "Bearer " + jwtUtil.generate(1L)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }
}
```

`BillParserSupportTest.java`（新建）：

```java
package com.balancewise.importbill;

import com.balancewise.importbill.parser.BillParserSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class BillParserSupportTest {

    @Test
    void thousandSeparatorAccepted() {
        // 合法千分位照常解析（UC-4.2 金额去千分位）
        assertEquals(0, new BigDecimal("1234.50")
                .compareTo(BillParserSupport.parseAmount("1,234.50")));
        assertEquals(0, new BigDecimal("1234567.00")
                .compareTo(BillParserSupport.parseAmount("1,234,567")));
    }

    @Test
    void malformedCommaRejected() {
        // 台账 #9：畸形 "1,2" 曾静默变成 12，必须拒绝
        assertThrows(IllegalArgumentException.class,
                () -> BillParserSupport.parseAmount("1,2"));
        assertThrows(IllegalArgumentException.class,
                () -> BillParserSupport.parseAmount("12,34.5"));
    }
}
```

`TransactionServiceTest.java` 追加（to 侧筛选用例，台账 #5）：

```java
    @Test
    void pageAccountFilterHitsToSide() {
        // 台账 #5：账户筛选须命中 to 侧（转入账户），此前仅有 from 侧用例
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        transactionService.create(1L, req("income", null, b.getId(), "100.00", null));
        transactionService.create(1L, req("expense", a.getId(), null, "50.00", null));
        transactionService.create(1L, req("transfer", a.getId(), b.getId(), "30.00", null));

        TransactionQuery q = new TransactionQuery();
        q.setAccountId(b.getId());
        var page = transactionService.page(1L, q);
        assertEquals(2, page.getTotal()); // 收入的 to + 转账的 to 都命中
    }
```

`TransactionApiTest.java` 的 `exportReturnsCsv` 追加响应头断言（方法体前部追加两行）：

```java
        MvcResult res = mockMvc.perform(get("/api/transactions/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
```

（文件头追加静态 import：`org.springframework.test.web.servlet.result.MockMvcResultMatchers.header`、`static org.hamcrest.Matchers.containsString`。）

`ImportBatchTest.java` 追加（TOCTOU 守护用例，需先 `@Autowired private com.balancewise.importbill.mapper.ImportBatchMapper importBatchMapper;`）：

```java
    @Test
    void rollbackSkipsReverseWhenStatusAlreadyRolledBack() {
        // 台账 #10 守护：并发赢家已把状态置 rolled_back 后，第二个请求必须拒绝且绝不二次冲销
        Long batchId = confirmedBatchWithOneExpense();
        Account cash = accountService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, 1L).eq(Account::getName, "现金"));
        // 模拟并发赢家提交：状态已是 rolled_back（余额已由赢家冲销回 0）
        ImportBatch winner = importBatchMapper.selectById(batchId);
        winner.setStatus("rolled_back");
        importBatchMapper.updateById(winner);
        accountService.getBaseMapper().update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Account>()
                .eq(Account::getId, cash.getId())
                .setSql("balance = 0.00"));

        BizException e = assertThrows(BizException.class, () -> importService.rollback(1L, batchId));
        assertTrue(e.getMessage().contains("已撤销"));
        assertEquals(0, BigDecimal.ZERO.compareTo(accountService.getById(cash.getId()).getBalance()));
    }
```

`StatsServiceTest.java` 追加（月桶最后一日边界，台账 #11）：

```java
    @Test
    void monthBucketIncludesLastDayTransactions() {
        // 台账 #11：落在月桶最后一日（9-30 23:59:59）的流水必须计入该月桶（排他上界 < 次日 00:00）
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        txn("expense", a.getId(), null, "66.00", LocalDateTime.of(2026, 9, 30, 23, 59, 59));
        txn("expense", a.getId(), null, "10.00", LocalDateTime.of(2026, 10, 1, 0, 0, 0));

        StatsTrendVo vo = statsService.trend(1L, trendQuery("month", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)));
        assertEquals(2, vo.getPoints().size());
        assertEquals("2026-09", vo.getPoints().get(0).getLabel());
        assertEquals(0, new BigDecimal("66.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, new BigDecimal("10.00").compareTo(vo.getPoints().get(1).getExpense()));
    }
```

- [ ] **Step 2: 跑测试确认红**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test -Dtest=CategoryApiTest,BillParserSupportTest`
Expected: `malformedCommaRejected` FAIL（"1,2" 现返回 12 不抛异常）；`missingKindReturns400` FAIL（现 code=500）；`invalidKindReturns400` FAIL（现返回空树 code=0）；其余新用例编译通过但逻辑待加固（to 侧/TOCTOU 守护/月桶在当前实现下预期可通过——它们是对既有正确行为的守护，红灯形态仅上述 3 项）

- [ ] **Step 3: ① categories 参数校验 400**

`CategoryController.java` 的 `tree` 方法整体替换为：

```java
    @GetMapping
    public Result<List<CategoryVo>> tree(@CurrentUser Long userId,
                                         @RequestParam(required = false) String kind) {
        // 台账 #1：缺 kind 曾抛 MissingServletRequestParameterException 落入全局 500
        if (kind == null || kind.isBlank()) {
            throw new BizException("缺少必填参数：kind");
        }
        if (!"expense".equals(kind) && !"income".equals(kind)) {
            throw new BizException("kind 必须是 income 或 expense");
        }
        return Result.ok(categoryService.tree(userId, kind));
    }
```

（文件头追加 import：`com.balancewise.common.BizException`。）

`GlobalExceptionHandler.java` 追加缺参兜底（防御纵深，覆盖将来其它端点）：

```java
    // 缺必填请求参数（@RequestParam 未标 required=false 时）从 500 转为友好 400
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return Result.fail(400, "缺少必填参数：" + e.getParameterName());
    }
```

- [ ] **Step 4: ② parseAmount 千分位加固**

`BillParserSupport.java` 的 `parseAmount` 方法整体替换为：

```java
    /**
     * 金额清洗：去 ¥ ￥ 元 与空白；千分位逗号仅允许标准形式（1,234.50），
     * 畸形 "1,2" 必须拒绝（台账 #9：曾静默去逗号变成 12）。
     */
    public static BigDecimal parseAmount(String raw) {
        String s = raw == null ? "" : raw
                .replace("¥", "").replace("￥", "").replace("元", "").trim();
        if (s.contains(",")) {
            if (!s.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
                throw new IllegalArgumentException("金额格式不正确：" + raw);
            }
            s = s.replace(",", "");
        }
        return new BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP);
    }
```

（调用方 AlipayParser/WechatParser 的行级 try/catch 会把该行记为 dropped 并标注原因，行为契约不变。）

- [ ] **Step 5: ③ export 响应头断言已随 Step 1 测试给出（响应头代码 `TransactionController:54` 既有，无改动）+ rollback TOCTOU 条件更新**

`ImportService.java` 的 `rollback` 方法整体替换为：

```java
    /**
     * 批次撤销（UC-4.11 / 规格 7.5）：单事务——
     * 条件更新抢占（台账 #10 TOCTOU 加固）：UPDATE ... SET status='rolled_back' WHERE id=? AND status='confirmed'，
     * 并发双撤销时第二个请求影响 0 行，直接拒绝，绝不二次冲销；
     * 置状态成功后逐条按当前值反向冲销（编辑过以当前值为准）→ 软删。撤销后可重新导入。
     */
    @Transactional
    public ImportBatch rollback(Long userId, Long batchId) {
        ImportBatch batch = ownedBatch(userId, batchId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int updated = importBatchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getId, batch.getId())
                .eq(ImportBatch::getStatus, "confirmed")
                .set(ImportBatch::getStatus, "rolled_back")
                .set(ImportBatch::getRolledBackAt, now));
        if (updated == 0) {
            throw new BizException("批次已撤销，请勿重复操作");
        }
        batch.setStatus("rolled_back");
        batch.setRolledBackAt(now);
        List<TransactionRecord> records = transactionRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getImportBatchId, batchId)
                        .eq(TransactionRecord::getIsDeleted, 0));
        for (TransactionRecord r : records) {
            transactionService.reverse(r);
            r.setIsDeleted(1);
            transactionRecordMapper.updateById(r);
        }
        return batch;
    }
```

- [ ] **Step 6: 跑全量测试确认绿并提交**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: 111/111（104 既有 + 7 新增；`exportReturnsCsv` 与既有 TOCTOU/边界行为用例均为回归确认）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "fix(p5): 后端 Minor 清算——categories 缺参 400/parseAmount 千分位加固/rollback TOCTOU 条件更新 + to侧/月桶末日/导出响应头测试"
```

---

### Task 5: 前端 Minor 清算批次（#13/#14/#15/#16/#17）

**Files:**
- Modify: `frontend/src/main.js`
- Modify: `frontend/src/views/DashboardView.vue`（时间列本地化）
- Modify: `frontend/src/views/StatsView.vue`（删死代码）
- Modify: `frontend/vite.config.js`（manualChunks）
- Delete: `frontend/src/views/PlaceholderView.vue`
- Test: 构建 + 控制台噪音目检（Task 6 e2e 统一做）

**Interfaces:**
- Consumes: 既有 `useChart`/`request`（无契约变化）。
- Produces: 无新契约；构建产物 echarts/element-plus 拆为独立 chunk（构建日志可见）。

- [ ] **Step 1: ① unhandled rejection 全局兜底（方案定死）**

`main.js` 的 `createApp(...)` 之前追加：

```js
// 统一兜底未处理的 Promise 拒绝：request.js 响应拦截器已统一弹出后端错误消息，
// 这里只阻止浏览器控制台 unhandledrejection 噪音（台账 #14，覆盖 Dashboard/Stats/Import 三页）
window.addEventListener('unhandledrejection', e => e.preventDefault())
```

- [ ] **Step 2: ② DashboardView 时间列本地化（#13）**

`DashboardView.vue` `<script setup>` 中 `fmtDate` 定义之后追加：

```js
// 后端 occurredAt 为 "yyyy-MM-dd HH:mm:ss"，截取本地化显示到分钟（台账 #13）
const fmtTime = s => (s ? String(s).slice(0, 16) : '—')
```

时间列模板替换为：

```html
        <el-table-column label="时间" width="150" :formatter="({ occurredAt }) => fmtTime(occurredAt)" />
```

（原 `<el-table-column prop="occurredAt" label="时间" width="165" />` 整行删除。）

- [ ] **Step 3: ③ 删除 PlaceholderView（#15）**

```bash
git rm frontend/src/views/PlaceholderView.vue
```

（已核实全仓库零引用：views、router、layouts 均无 import。）

- [ ] **Step 4: ④ vite manualChunks 拆包（#16）**

`vite.config.js` 整体替换为：

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 台账 #16：echarts/element-plus 全量引入改走 manualChunks 独立分包，
        // 业务 chunk 不再携带图表与组件库（不改 useChart import 语义，构建通过即可）
        manualChunks: {
          echarts: ['echarts'],
          element: ['element-plus', '@element-plus/icons-vue'],
        },
      },
    },
  },
})
```

- [ ] **Step 5: ⑤ StatsView 死代码清理（#17）**

`StatsView.vue` 的 `range` computed 中 `lastMonth` 分支删除未使用的 `first` 变量行：

```js
  if (preset.value === 'lastMonth') {
    const last = new Date(now.getFullYear(), now.getMonth(), 0)
    return { start: fmtDate(new Date(last.getFullYear(), last.getMonth(), 1)), end: fmtDate(last) }
  }
```

- [ ] **Step 6: 构建验证并提交**

Run: `cd frontend && npm run build 2>&1 | tail -8`
Expected: 构建成功，产物列表出现独立的 `echarts` 与 `element` chunk

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "chore(p5): 前端 Minor 清算——unhandledrejection 兜底/时间列本地化/删 PlaceholderView/echarts 分包/死代码清理"
```

---

### Task 6: e2e 全链路验收 + README + tag + 库终态/进程回收

**Files:**
- Modify: `README.md`（功能清单补 UC-2.6 对账、UC-1.5/1.6 用户中心）
- Test: 全量回归 + curl e2e + 前端构建 + 浏览器目检

**Interfaces:**
- Consumes: Task 1~5 全部产出。
- Produces: tag `p5-polish-v1`；README 终态；干净的 MySQL 库与回收的进程。

- [ ] **Step 1: 全量回归 + 前端构建**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: **111/111 全绿**

Run: `cd frontend && npm run build 2>&1 | tail -5`
Expected: 构建成功

- [ ] **Step 2: 起后端，curl 验收对账与用户中心端点**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17" && export PATH="$JAVA_HOME/bin:$PATH"
powershell Start-Process -FilePath ".tools/apache-maven-3.9.9/bin/mvn.cmd" `
  -ArgumentList '-s','.tools/maven-settings.xml','-f','backend/pom.xml','spring-boot:run' `
  -RedirectStandardOutput work/p5-backend.log -RedirectStandardError work/p5-backend-err.log -NoNewWindow
sleep 45 && tail -3 work/p5-backend.log   # 等 Started BalanceWiseApplication
```

```bash
# 自注册验收专用账号（注册即初始化预置分类+现金账户，不依赖任何既有账号；已存在则 400 不影响后续）
curl -s -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"p5e2e","password":"123456","confirmPassword":"123456","nickname":"P5验收"}'
curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"p5e2e","password":"123456"}' > work/p5-login.json
TOKEN=$(python -c "import json;print(json.load(open('work/p5-login.json'))['data']['token'])")
# 期望：login 返回 code=0 且 data.token 非空

# 建测试账户 → 对账（正差额）
curl -s -X POST http://localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"name":"P5对账验收卡","type":"asset","subType":"debit_card","initialBalance":100,"remark":""}' > work/p5-acc.json
ACC=$(python -c "import json;print(json.load(open('work/p5-acc.json'))['data']['id'])")
curl -s -X POST "http://localhost:8080/api/accounts/$ACC/reconcile" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"actualBalance":"130.00"}'
# 期望：{"code":0,...,"data":{...,"diff":30.00,"correctionType":"income",...}}

# 再对账同额 → 400 无需校正（幂等）
curl -s -X POST "http://localhost:8080/api/accounts/$ACC/reconcile" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"actualBalance":"130.00"}'
# 期望：code=400，message 含"无需校正"

# 裸 GET /api/categories → 400（台账 #1 修复验收）
curl -s "http://localhost:8080/api/categories" -H "Authorization: Bearer $TOKEN"
# 期望：code=400，message 含 kind

# 改昵称 → code=0；改密错误原密码 → code=400
curl -s -X PUT http://localhost:8080/api/user/nickname -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"nickname":"P5验收"}'
curl -s -X PUT http://localhost:8080/api/user/password -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"oldPassword":"wrong","newPassword":"654321"}'
# 期望：code=400 原密码不正确

# 流水列表出现 source=reconcile 的校正流水（可追溯）
curl -s "http://localhost:8080/api/transactions?size=5" -H "Authorization: Bearer $TOKEN" | grep -o '"source":"reconcile"' | head -1
# 期望：至少 1 处命中
```

- [ ] **Step 3: 前端 dev 起服务，浏览器目检（对账入口/用户中心/控制台噪音）**

```bash
cd frontend && npm run dev > ../work/p5-frontend.log 2>&1 &
sleep 8 && curl -s http://localhost:5173/ | head -3
```

浏览器目检清单（逐项确认）：
1. 账户页每个账户行出现「对账」按钮；点开弹窗显示账面余额、录入实际余额、差额实时预览（正绿负红）
2. 差额为 0 时确认按钮禁用；提交后提示生成校正流水、余额刷新
3. 侧边栏出现「用户中心」，页面可改昵称（头部昵称即时刷新）与改密（改密后跳登录页）
4. 仪表盘「最近流水」时间列显示为 `YYYY-MM-DD HH:mm`
5. 打开各页面切换，浏览器控制台无 unhandled rejection 红色噪音

目检完成后回收前端 dev（按 5173 监听端口定位 node 进程）：

```bash
powershell "$pids = (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue).OwningProcess | Sort-Object -Unique; $pids | ForEach-Object { Stop-Process -Id \$_ -Force }"
```

- [ ] **Step 4: 库终态清理 + 进程回收 + README + tag**

```bash
# 回收后端进程（java 应用进程与 mvn spring-boot:run 父进程）
powershell Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -match 'BalanceWise|spring-boot:run' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 清理验收账号及其全部数据（库终态干净；表间为逻辑外键，按 user_id 逐表删除）
mysql -uroot -proot balancewise -e "SET @u=(SELECT id FROM user WHERE username='p5e2e'); DELETE FROM transaction_record WHERE user_id=@u; DELETE FROM import_batch WHERE user_id=@u; DELETE FROM account WHERE user_id=@u; DELETE FROM category WHERE user_id=@u; DELETE FROM user WHERE id=@u;"
mysql -uroot -proot balancewise -e "SELECT COUNT(*) AS leftover FROM user WHERE username='p5e2e';"
# 期望：leftover = 0（验收账号及其全部数据已清）
```

`README.md` 的 `## 当前功能` 列表末尾（`- 统计：…多维筛选）` 行之后）追加两行：

```markdown
- 余额校正（对账）：录入账户实际余额，差额自动生成「余额校正」流水（收入/支出校正，来源标记 reconcile，可追溯可冲销）
- 用户中心：修改昵称、修改密码（旧密码校验，改密后需重新登录）
```

```bash
git add README.md
git -c user.name="李东升" -c user.email="lids@local" commit -m "docs(p5): README 功能清单补充对账与用户中心"

git tag -f p5-polish-v1
git log --oneline -1 && git tag | grep p5
```

Expected: tag `p5-polish-v1` 指向最终提交；`git status` 工作区干净（用户资料 `scholar_*.csv`、`work/` 不入库）。

- [ ] **Step 5: 全量回归终验**

Run: `.tools/apache-maven-3.9.9/bin/mvn.cmd -s .tools/maven-settings.xml -f backend/pom.xml test`
Expected: **111/111 全绿**（README 提交后终态）

---

## Self-Review（计划自审结果）

**1. 规格覆盖：**
- §3 UC-2.6 对账（后端+前端）：Task 1 + Task 2，口径逐条对齐规格 7.6（差额=实际−当前、为 0 拒绝、差额正记收入负记支出、分类「其他」、备注自动、source=reconcile、走 7.1 余额联动、可查询/可编辑/可删除冲销）✅
- §3 UC-1.5/UC-1.6 用户中心：后端签名已核实存在（UserController/UserService/ChangePasswordRequest/ChangeNicknameRequest），Task 3 只做前端 ✅
- §6 端点表：POST /api/accounts/{id}/reconcile、PUT /api/user/password、PUT /api/user/nickname 三条全部落地或核实 ✅
- §7.6 流程：Task 1 Step 5 实现逐条对应（归属校验→差额→校正流水→余额联动），「可编辑、可删除冲销」由既有 TransactionService.update/delete 天然承载，无新增代码 ✅
- Minor 台账 20 项：7 项核实已修（#2/#3/#4a/#4c/#6/#7/#8，开头核对表逐条给出现状证据）→ 回归确认；3 项后端实修（#1 categories 400、#9 parseAmount 加固、#10 rollback TOCTOU）+ 3 项后端补测试（#4b 响应头断言、#5 to 侧用例、#11 月桶末日用例）→ Task 4；6 项前端（#13~#17/#20）+ #18（并入 Task 2）+ #19（Task 3）→ Task 2/3/5。逐项可溯源 ✅

**2. 占位符扫描：** 无 TBD/TODO/"适当处理"/"类似 Task N"；每个代码步骤均含完整可编译代码与精确命令；`curl` 验收给出期望响应文案 ✅

**3. 类型一致性：**
- `ReconcileService.reconcile(Long, Long, ReconcileRequest): ReconcileResultVo` 在 Task 1 定义、Task 2 前端按 REST 契约消费，`diff/correctionType/transactionId` 字段名前后一致 ✅
- `importRecord(userId, source, externalId, importBatchId, req)` 形参顺序与 TransactionService 实际签名一致 ✅
- 测试辅助函数沿用既有文件真实签名：`TransactionServiceTest.acc(name,type,subType,balance)`/`req(type,from,to,amount,categoryId)`、`StatsServiceTest.acc/txn/trendQuery`、`ImportBatchTest.confirmedBatchWithOneExpense`，全部与现有测试文件核实一致 ✅
- Task 4 中 `ImportBatchMapper.update(null, LambdaUpdateWrapper)` 与 P2 delete 幂等修法（TransactionService.delete:212）同款模式 ✅

**4. 测试基线预期：** 93（基线）→ Task 1 后 104（+11：ReconcileServiceTest 7 + ReconcileApiTest 4）→ Task 4 后 111（+7：CategoryApiTest 2 + BillParserSupportTest 2 + to 侧 1 + TOCTOU 守护 1 + 月桶末日 1；exportReturnsCsv 为既有用例追加断言不计数）→ 终态 **111/111**。Task 2/3/5/6 不产生后端测试。
