# P2 记账模块实施计划（余额联动 / 冲销事务）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现记账模块（UC-3.1 ~ UC-3.6）：记支出/收入/转账（余额联动）、流水列表（分页+筛选）、编辑（冲销+入账两步法）、删除（软删+冲销）、CSV 导出，并完成前后端联调验收。

**Architecture:** 沿用 P1 分层（Controller/Service/Mapper/Entity/VO）。余额联动严格走 `AccountMapper.addBalance` 原子 SQL（禁止读改写）；所有账务写操作 `@Transactional`；流水统一 from 减 / to 加（支出仅有 from、收入仅有 to、转账两者）；编辑=旧记录反向冲销+新记录入账；删除=冲销+`is_deleted=1`。

**Tech Stack:** Spring Boot 3.3 + MyBatis-Plus 3.5 + H2(测试)/MySQL 8 + JUnit 5 + MockMvc；Vue 3 + Element Plus + axios。

## Global Constraints

- JDK 17：`C:\Program Files\Java\jdk-17`（每个 Bash 调用重新 `export JAVA_HOME`）
- Maven：`D:\KimiData\kimi\Workspaces\BalanceWise\.tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\KimiData\kimi\Workspaces\BalanceWise\.tools\maven-settings.xml`
- git 提交身份：`-c user.name="李东升" -c user.email="lids@local"`
- 统一返回 `Result{code,message,data}`；code=0 成功、400 业务错误、401 未登录（401 仅保留给 JWT 失效）
- 表结构严格按规格 §5.2，**不再改 schema**（transaction_record 表 P1 已建好，含全部字段）
- 余额规则（规格 §5.4）：负债余额为负；任何流水 = from 减 / to 加；净资产 = SUM(balance)
- 测试约定：`@SpringBootTest @ActiveProfiles("test")`，Service 测试加类级 `@Transactional` 回滚
- 后端包根 `com.balancewise`；流水模块代码放 `com.balancewise.record.*`（P1 已建 entity 和 mapper）
- Git Bash 内嵌中文 JSON 会按 GBK 发送 → 含中文的 curl 请求体写 UTF-8 文件用 `curl -d @文件`
- Bash 不支持后台任务；后台进程用 `powershell Start-Process` 分离，验证完回收
- 阶段分支：`p2-ledger`（从 main 切出）；阶段 tag：`p2-ledger-v1`

---

### Task 1: P1 遗留加固 + 阶段分支

**Files:**
- Modify: `backend/src/main/java/com/balancewise/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/balancewise/auth/AuthApiTest.java`

**Interfaces:**
- Consumes: P1 全部既有代码。
- Produces: 分支 `p2-ledger`；配置支持环境变量覆盖（`DB_URL`/`DB_USER`/`DB_PASSWORD`/`JWT_SECRET`）；`DuplicateKeyException` → code=400「数据已存在，请勿重复提交」。

- [ ] **Step 1: 建分支**

```bash
cd D:\KimiData\kimi\Workspaces\BalanceWise
git checkout main && git checkout -b p2-ledger
```

- [ ] **Step 2: GlobalExceptionHandler 换 SLF4J + 重复键处理**

将 `handleOther` 的 `e.printStackTrace()` 换成日志，并新增 `DuplicateKeyException` 处理器。完整文件改为：

```java
package com.balancewise.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    // 未匹配的请求路径由静态资源处理器抛出 NoResourceFoundException，
    // 必须保持 404 语义，不能落入 handleOther 变成 HTTP 200 + code 500
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        return Result.fail(404, "资源不存在");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(400, msg);
    }

    // 并发撞唯一索引（如并发注册同名用户）从 500 转为友好 400
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("duplicate key: {}", e.getMessage());
        return Result.fail(400, "数据已存在，请勿重复提交");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("unexpected error", e);
        return Result.fail(500, "系统异常，请稍后重试");
    }
}
```

- [ ] **Step 3: application.yml 敏感配置环境变量化**

datasource 与 jwt 三段改为（默认值保持现状，本地零改动可用）：

```yaml
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/balancewise?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
```

```yaml
jwt:
  secret: ${JWT_SECRET:balancewise-dev-secret-key-please-change-in-production-0123456789abcdef}
  expire-hours: 72
```

- [ ] **Step 4: 认证测试加固（先写测试）**

在 `AuthApiTest` 中新增两个测试（既有三个保留）：

```java
    @Test
    void noTokenReturns401WithBody() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().is(401))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("\"code\":401"));
        assertTrue(body.contains("未登录"));
    }

    @Test
    void tamperedTokenReturns401() throws Exception {
        String token = jwtUtil.generate(7L);
        mockMvc.perform(get("/api/test/ping")
                        .header("Authorization", "Bearer " + token + "tampered"))
                .andExpect(status().is(401));
    }
```

（`MvcResult` 已 import，无需新增。）

- [ ] **Step 5: 回归并提交**

Run: `cd backend && mvn test`
Expected: 24/24 通过（22 既有 + 2 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "chore(p2): P1 加固——日志/DuplicateKey 友好化/配置环境变量化/认证测试断言"
```

---

### Task 2: 记账核心（记一笔 + 余额联动）

**Files:**
- Create: `backend/src/main/java/com/balancewise/record/dto/TransactionRequest.java`
- Modify: `backend/src/main/java/com/balancewise/account/mapper/AccountMapper.java`
- Create: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`
- Test: `backend/src/test/java/com/balancewise/record/TransactionServiceTest.java`

**Interfaces:**
- Consumes: `AccountMapper`（Task 2 新增 `addBalance`）、`CategoryMapper`、`TransactionRecordMapper`（P1）、`AccountService.create`（测试造数）。
- Produces: `TransactionService.create(Long userId, TransactionRequest req)` 返回 `TransactionRecord`；`TransactionRequest` 字段：`type/fromAccountId/toAccountId/amount/categoryId/occurredAt/counterparty/item`（Task 3/6 复用）。Task 3 依赖本任务的 `applyBalance`、`usableAccount`、`validateCategory`、`resolveAccounts`、`amount` 私有方法所在的服务类。

- [ ] **Step 1: 写 TransactionRequest DTO**

```java
package com.balancewise.record.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TransactionRequest {

    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "expense|income|transfer", message = "类型必须是 expense/income/transfer")
    private String type;

    private Long fromAccountId;
    private Long toAccountId;

    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "金额必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
    private BigDecimal amount;

    private Long categoryId;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
}
```

- [ ] **Step 2: AccountMapper 增加原子余额增减**

```java
package com.balancewise.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.account.entity.Account;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface AccountMapper extends BaseMapper<Account> {

    /**
     * 余额联动唯一入口：原子增减 balance，禁止业务代码读改写余额。
     * 负债账户余额为负（规格 5.4 规则 2），任何流水 = from 减 / to 加。
     * NOW() 在 MySQL 8 与 H2(MySQL 模式) 均可用。
     */
    @Update("UPDATE account SET balance = balance + #{delta}, updated_at = NOW() WHERE id = #{id}")
    int addBalance(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
```

- [ ] **Step 3: 写失败测试（负债不变量是论文核心，必须白盒覆盖）**

```java
package com.balancewise.record;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionServiceTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
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

    private Category cat(String name, String kind, Long parentId) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setParentId(parentId);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return c;
    }

    private TransactionRequest req(String type, Long from, Long to, String amount, Long categoryId) {
        TransactionRequest r = new TransactionRequest();
        r.setType(type);
        r.setFromAccountId(from);
        r.setToAccountId(to);
        r.setAmount(new BigDecimal(amount));
        r.setCategoryId(categoryId);
        r.setOccurredAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        return r;
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountService.getById(accountId).getBalance();
    }

    @Test
    void expenseReducesAssetBalance() {
        Account a = acc("储蓄卡", "asset", "debit_card", "1000.00");
        transactionService.create(1L, req("expense", a.getId(), null, "200.00", null));
        assertEquals(0, new BigDecimal("800.00").compareTo(balanceOf(a.getId())));
    }

    @Test
    void incomeIncreasesAssetBalance() {
        Account a = acc("钱包", "asset", "cash", "0.00");
        transactionService.create(1L, req("income", null, a.getId(), "3500.00", null));
        assertEquals(0, new BigDecimal("3500.00").compareTo(balanceOf(a.getId())));
    }

    @Test
    void transferMovesBetweenTwoAccounts() {
        Account from = acc("卡A", "asset", "debit_card", "500.00");
        Account to = acc("卡B", "asset", "debit_card", "100.00");
        TransactionRecord r = transactionService.create(1L,
                req("transfer", from.getId(), to.getId(), "150.00", null));
        assertNull(r.getCategoryId());
        assertEquals(0, new BigDecimal("350.00").compareTo(balanceOf(from.getId())));
        assertEquals(0, new BigDecimal("250.00").compareTo(balanceOf(to.getId())));
    }

    @Test
    void expenseOnLiabilityMakesDebtMoreNegative() {
        // 负债消费：信用卡欠款 5000，刷卡花 1000 → -6000
        Account card = acc("信用卡", "liability", "credit_card", "5000.00");
        transactionService.create(1L, req("expense", card.getId(), null, "1000.00", null));
        assertEquals(0, new BigDecimal("-6000.00").compareTo(balanceOf(card.getId())));
    }

    @Test
    void incomeOnLiabilityReducesDebt() {
        // 还款：向信用卡转入 2000 → 欠款从 -5000 变为 -3000
        Account card = acc("信用卡", "liability", "credit_card", "5000.00");
        transactionService.create(1L, req("income", null, card.getId(), "2000.00", null));
        assertEquals(0, new BigDecimal("-3000.00").compareTo(balanceOf(card.getId())));
    }

    @Test
    void transferFromAssetToLiabilityIsRepayment() {
        // 储蓄卡还花呗：from 减、to 加，负债余额向 0 收敛
        Account card = acc("储蓄卡", "asset", "debit_card", "10000.00");
        Account huabei = acc("花呗", "liability", "huabei", "500.00");
        transactionService.create(1L, req("transfer", card.getId(), huabei.getId(), "500.00", null));
        assertEquals(0, new BigDecimal("9500.00").compareTo(balanceOf(card.getId())));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(huabei.getId())));
    }

    @Test
    void transferSameAccountRejected() {
        Account a = acc("卡A", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> transactionService.create(1L, req("transfer", a.getId(), a.getId(), "10.00", null)));
        assertEquals("转出与转入账户不能相同", e.getMessage());
    }

    @Test
    void archivedAccountRejected() {
        Account a = acc("旧卡", "asset", "debit_card", "100.00");
        accountService.archive(1L, a.getId(), true);
        BizException e = assertThrows(BizException.class,
                () -> transactionService.create(1L, req("expense", a.getId(), null, "10.00", null)));
        assertTrue(e.getMessage().contains("已归档"));
    }

    @Test
    void categoryKindMustMatchType() {
        Account a = acc("钱包", "asset", "cash", "100.00");
        Category expenseCat = cat("餐饮", "expense", null);
        Category incomeCat = cat("工资", "income", null);
        // 支出用收入分类 → 拒绝
        assertThrows(BizException.class,
                () -> transactionService.create(1L, req("expense", a.getId(), null, "10.00", incomeCat.getId())));
        // 支出用支出分类 → 通过
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", expenseCat.getId()));
        assertEquals(expenseCat.getId(), r.getCategoryId());
    }

    @Test
    void cannotUseOtherUsersAccount() {
        Account mine = acc("我的卡", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> transactionService.create(2L, req("expense", mine.getId(), null, "10.00", null)));
        assertEquals("支出账户不存在", e.getMessage());
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `mvn test -Dtest=TransactionServiceTest`
Expected: 编译失败（`TransactionService`/`TransactionRequest` 不存在）

- [ ] **Step 5: 实现 TransactionService.create**

```java
package com.balancewise.record.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
public class TransactionService extends ServiceImpl<TransactionRecordMapper, TransactionRecord> {

    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;

    public TransactionService(AccountMapper accountMapper, CategoryMapper categoryMapper) {
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * 余额联动（规格 5.4 规则 1/2）：原子增减，任何流水 = from 减、to 加。
     * 负债账户余额为负，因此还款/负债消费方向全部自洽。
     */
    protected void applyBalance(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        if (fromAccountId != null) {
            accountMapper.addBalance(fromAccountId, amount.negate());
        }
        if (toAccountId != null) {
            accountMapper.addBalance(toAccountId, amount);
        }
    }

    protected Account usableAccount(Long userId, Long accountId, String label) {
        Account a = accountMapper.selectById(accountId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BizException(label + "不存在");
        }
        if (a.getIsArchived() == 1) {
            throw new BizException("账户「" + a.getName() + "」已归档，无法记账");
        }
        return a;
    }

    protected void validateCategory(Long userId, String type, Long categoryId) {
        if (categoryId == null || "transfer".equals(type)) {
            return;
        }
        Category c = categoryMapper.selectById(categoryId);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BizException("分类不存在");
        }
        String expectKind = "expense".equals(type) ? "expense" : "income";
        if (!expectKind.equals(c.getKind())) {
            throw new BizException("分类类型与流水类型不匹配");
        }
    }

    /** 按类型解析并校验 from/to，返回 [from, to]（收入 from 为 null，支出 to 为 null） */
    protected Long[] resolveAccounts(Long userId, TransactionRequest req) {
        Long from = null;
        Long to = null;
        switch (req.getType()) {
            case "expense" -> {
                if (req.getFromAccountId() == null) {
                    throw new BizException("请选择支出账户");
                }
                usableAccount(userId, req.getFromAccountId(), "支出账户");
                from = req.getFromAccountId();
            }
            case "income" -> {
                if (req.getToAccountId() == null) {
                    throw new BizException("请选择收入账户");
                }
                usableAccount(userId, req.getToAccountId(), "收入账户");
                to = req.getToAccountId();
            }
            case "transfer" -> {
                if (req.getFromAccountId() == null || req.getToAccountId() == null) {
                    throw new BizException("转账需要转出和转入账户");
                }
                if (req.getFromAccountId().equals(req.getToAccountId())) {
                    throw new BizException("转出与转入账户不能相同");
                }
                usableAccount(userId, req.getFromAccountId(), "转出账户");
                usableAccount(userId, req.getToAccountId(), "转入账户");
                from = req.getFromAccountId();
                to = req.getToAccountId();
            }
            default -> throw new BizException("不支持的流水类型");
        }
        return new Long[]{from, to};
    }

    protected BigDecimal normalizeAmount(TransactionRequest req) {
        return req.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public TransactionRecord create(Long userId, TransactionRequest req) {
        Long[] accounts = resolveAccounts(userId, req);
        validateCategory(userId, req.getType(), req.getCategoryId());
        BigDecimal amount = normalizeAmount(req);

        TransactionRecord r = new TransactionRecord();
        r.setUserId(userId);
        r.setType(req.getType());
        r.setFromAccountId(accounts[0]);
        r.setToAccountId(accounts[1]);
        r.setAmount(amount);
        r.setCategoryId("transfer".equals(req.getType()) ? null : req.getCategoryId());
        r.setOccurredAt(req.getOccurredAt() != null ? req.getOccurredAt() : LocalDateTime.now());
        r.setCounterparty(req.getCounterparty());
        r.setItem(req.getItem());
        r.setSource("manual");
        r.setIsDeleted(0);
        save(r);

        applyBalance(accounts[0], accounts[1], amount);
        return r;
    }
}
```

注意：`Page` 的 import 本任务暂未使用，先不引入（Task 4 再加），避免 unused import。

- [ ] **Step 6: 跑测试确认通过并提交**

Run: `mvn test`
Expected: 34/34 通过（24 既有 + 10 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 记账核心——三种类型余额联动（负债不变量白盒测试）"
```

---

### Task 3: 编辑与删除流水（冲销事务）

**Files:**
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`
- Test: `backend/src/test/java/com/balancewise/record/TransactionServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 2 的 `applyBalance`/`resolveAccounts`/`validateCategory`/`normalizeAmount`。
- Produces: `TransactionService.update(Long userId, Long id, TransactionRequest req)` 返回更新后的 `TransactionRecord`；`TransactionService.delete(Long userId, Long id)`；私有 `owned(userId, id)`（Task 4/5 复用）。

- [ ] **Step 1: 写失败测试（追加到 TransactionServiceTest）**

```java
    @Test
    void editChangeAccountReversesOldAndAppliesNew() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "debit_card", "500.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        // 编辑：账户从 A 换到 B
        transactionService.update(1L, r.getId(), req("expense", b.getId(), null, "200.00", null));
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId())));
        assertEquals(0, new BigDecimal("300.00").compareTo(balanceOf(b.getId())));
    }

    @Test
    void editChangeAmountAndType() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "debit_card", "0.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        // 类型 expense → income，账户从 A 换到 B，金额 200 → 300
        TransactionRecord updated = transactionService.update(1L, r.getId(),
                req("income", null, b.getId(), "300.00", null));
        assertEquals("income", updated.getType());
        assertEquals(0, new BigDecimal("1200.00").compareTo(balanceOf(a.getId()))); // 冲销加回
        assertEquals(0, new BigDecimal("300.00").compareTo(balanceOf(b.getId())));
    }

    @Test
    void deleteReversesBalanceAndSoftDeletes() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        transactionService.delete(1L, r.getId());
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId())));
        TransactionRecord after = transactionService.getById(r.getId());
        assertEquals(1, after.getIsDeleted());
    }

    @Test
    void deleteTransferReversesBothAccounts() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "debit_card", "0.00");
        TransactionRecord r = transactionService.create(1L,
                req("transfer", a.getId(), b.getId(), "400.00", null));
        transactionService.delete(1L, r.getId());
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId())));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(b.getId())));
    }

    @Test
    void cannotEditOrDeleteOtherUsersRecord() {
        Account a = acc("我的卡", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        BizException e1 = assertThrows(BizException.class,
                () -> transactionService.update(2L, r.getId(), req("expense", a.getId(), null, "1.00", null)));
        assertEquals("流水不存在", e1.getMessage());
        BizException e2 = assertThrows(BizException.class,
                () -> transactionService.delete(2L, r.getId()));
        assertEquals("流水不存在", e2.getMessage());
    }

    @Test
    void editKeepsSourceAndExternalId() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        r.setSource("alipay");
        r.setExternalId("202610010001");
        transactionService.updateById(r);
        transactionService.update(1L, r.getId(), req("expense", a.getId(), null, "250.00", null));
        TransactionRecord after = transactionService.getById(r.getId());
        assertEquals("alipay", after.getSource());
        assertEquals("202610010001", after.getExternalId());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=TransactionServiceTest`
Expected: 编译失败（`update`/`delete` 方法不存在）

- [ ] **Step 3: 实现 update / delete（追加到 TransactionService）**

```java
    protected TransactionRecord owned(Long userId, Long id) {
        TransactionRecord r = getById(id);
        if (r == null || !r.getUserId().equals(userId) || r.getIsDeleted() == 1) {
            throw new BizException("流水不存在");
        }
        return r;
    }

    /**
     * 编辑流水（规格 7.2）：事务内两步法——旧记录反向冲销（旧 from 加回、旧 to 减回），
     * 新记录按新值入账。天然支持换账户、变更类型（expense↔income↔transfer）。
     * source/externalId/importBatchId 保持不变（导入溯源信息不可因编辑丢失）。
     */
    @Transactional
    public TransactionRecord update(Long userId, Long id, TransactionRequest req) {
        TransactionRecord old = owned(userId, id);
        Long[] accounts = resolveAccounts(userId, req);
        validateCategory(userId, req.getType(), req.getCategoryId());
        BigDecimal amount = normalizeAmount(req);

        // 旧记录反向冲销
        applyBalance(old.getFromAccountId(), old.getToAccountId(), old.getAmount().negate());

        old.setType(req.getType());
        old.setFromAccountId(accounts[0]);
        old.setToAccountId(accounts[1]);
        old.setAmount(amount);
        old.setCategoryId("transfer".equals(req.getType()) ? null : req.getCategoryId());
        if (req.getOccurredAt() != null) {
            old.setOccurredAt(req.getOccurredAt());
        }
        old.setCounterparty(req.getCounterparty());
        old.setItem(req.getItem());
        updateById(old);

        // 新记录入账
        applyBalance(accounts[0], accounts[1], amount);
        return old;
    }

    /**
     * 删除流水（规格 7.3）：事务内反向冲销余额 → is_deleted=1（软删除，可追溯）。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        TransactionRecord r = owned(userId, id);
        applyBalance(r.getFromAccountId(), r.getToAccountId(), r.getAmount().negate());
        r.setIsDeleted(1);
        updateById(r);
    }
```

- [ ] **Step 4: 跑测试确认通过并提交**

Run: `mvn test`
Expected: 40/40 通过

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 流水编辑两步法冲销与软删除冲销"
```

---

### Task 4: 流水分页查询（筛选 + 名称填充）

**Files:**
- Create: `backend/src/main/java/com/balancewise/record/dto/TransactionQuery.java`
- Create: `backend/src/main/java/com/balancewise/record/vo/TransactionVo.java`
- Create: `backend/src/main/java/com/balancewise/common/PageResult.java`
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`
- Test: `backend/src/test/java/com/balancewise/record/TransactionServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 3 的 `owned` 无关；本任务用 `buildWrapper`/`enrich`。
- Produces: `TransactionService.page(Long userId, TransactionQuery q)` 返回 `PageResult<TransactionVo>`；`TransactionQuery` 字段（Task 5/6 复用）：`page/size/type/accountId/categoryId/start/end/keyword`；`TransactionVo` 字段（Task 6/8 复用）：`id/type/fromAccountId/fromAccountName/toAccountId/toAccountName/amount/categoryId/categoryName/occurredAt/counterparty/item/source/externalId/importBatchId`；`PageResult<T>{total, records}`（common 包，后续模块复用）。

- [ ] **Step 1: 写失败测试（追加）**

```java
    @Test
    void pageFiltersByTypeAccountAndKeyword() {
        Account a = acc("卡A", "asset", "debit_card", "10000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        Category food = cat("餐饮", "expense", null);
        transactionService.create(1L, req("expense", a.getId(), null, "20.00", food.getId()));
        TransactionRequest r2 = req("income", null, b.getId(), "3000.00", null);
        r2.setCounterparty("公司");
        r2.setItem("十月工资");
        transactionService.create(1L, r2);

        // 默认：全部、倒序
        TransactionQuery q = new TransactionQuery();
        PageResult<TransactionVo> all = transactionService.page(1L, q);
        assertEquals(2, all.getTotal());

        // 按类型
        TransactionQuery byType = new TransactionQuery();
        byType.setType("income");
        assertEquals(1, transactionService.page(1L, byType).getTotal());

        // 按账户（from 或 to 命中均可）
        TransactionQuery byAcc = new TransactionQuery();
        byAcc.setAccountId(a.getId());
        PageResult<TransactionVo> page = transactionService.page(1L, byAcc);
        assertEquals(1, page.getTotal());
        assertEquals("卡A", page.getRecords().get(0).getFromAccountName());
        assertEquals("餐饮", page.getRecords().get(0).getCategoryName());

        // 按关键字（对方/备注模糊）
        TransactionQuery byKw = new TransactionQuery();
        byKw.setKeyword("工资");
        assertEquals(1, transactionService.page(1L, byKw).getTotal());
    }

    @Test
    void pageExcludesSoftDeletedAndOtherUsers() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", null));
        transactionService.create(2L, req("expense", a.getId(), null, "10.00", null)); // 他人
        transactionService.delete(1L, r.getId()); // 自己删除一条
        assertEquals(1, transactionService.page(1L, new TransactionQuery()).getTotal());
    }

    @Test
    void pageFiltersByDateRange() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        transactionService.create(1L, req("expense", a.getId(), null, "10.00", null)); // 2026-10-01
        TransactionRequest r2 = req("expense", a.getId(), null, "20.00", null);
        r2.setOccurredAt(LocalDateTime.of(2026, 11, 15, 8, 0));
        transactionService.create(1L, r2);

        TransactionQuery q = new TransactionQuery();
        q.setStart(java.time.LocalDate.of(2026, 10, 1));
        q.setEnd(java.time.LocalDate.of(2026, 10, 31));
        assertEquals(1, transactionService.page(1L, q).getTotal());
    }
```

（`TransactionVo`/`PageResult`/`TransactionQuery` 顶部 import 随测试补充：`com.balancewise.common.PageResult`、`com.balancewise.record.vo.TransactionVo`、`java.time.LocalDate`。）

- [ ] **Step 2: 写三个新类**

`TransactionQuery.java`：

```java
package com.balancewise.record.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TransactionQuery {
    private long page = 1;
    private long size = 20;
    /** expense / income / transfer，空 = 全部 */
    private String type;
    /** 命中 from 或 to 任一账户 */
    private Long accountId;
    /** 精确匹配该分类（前端传叶子分类 id） */
    private Long categoryId;
    /** 含当天 00:00:00 起 */
    private LocalDate start;
    /** 含当天 23:59:59 止 */
    private LocalDate end;
    /** 对方/备注模糊 */
    private String keyword;
}
```

`TransactionVo.java`：

```java
package com.balancewise.record.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TransactionVo {
    private Long id;
    private String type;
    private Long fromAccountId;
    private String fromAccountName;
    private Long toAccountId;
    private String toAccountName;
    private BigDecimal amount;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    private String source;
    private String externalId;
    private Long importBatchId;
}
```

`PageResult.java`（common 包，后续模块复用）：

```java
package com.balancewise.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageResult<T> {
    private long total;
    private List<T> records;
}
```

- [ ] **Step 3: TransactionService 增加 page（含 buildWrapper/enrich，Task 5 复用）**

```java
    /**
     * 构建流水查询条件（分页与导出共用，Task 5 复用）。
     * 只查 is_deleted=0；账户筛选命中 from 或 to；关键字模糊匹配对方/备注。
     */
    protected com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord> buildWrapper(Long userId, TransactionQuery q) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        w.eq(TransactionRecord::getUserId, userId).eq(TransactionRecord::getIsDeleted, 0);
        if (q.getType() != null && !q.getType().isBlank()) {
            w.eq(TransactionRecord::getType, q.getType());
        }
        if (q.getAccountId() != null) {
            w.and(x -> x.eq(TransactionRecord::getFromAccountId, q.getAccountId())
                    .or().eq(TransactionRecord::getToAccountId, q.getAccountId()));
        }
        if (q.getCategoryId() != null) {
            w.eq(TransactionRecord::getCategoryId, q.getCategoryId());
        }
        if (q.getStart() != null) {
            w.ge(TransactionRecord::getOccurredAt, q.getStart().atStartOfDay());
        }
        if (q.getEnd() != null) {
            w.le(TransactionRecord::getOccurredAt, q.getEnd().atTime(23, 59, 59));
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            w.and(x -> x.like(TransactionRecord::getCounterparty, q.getKeyword())
                    .or().like(TransactionRecord::getItem, q.getKeyword()));
        }
        return w;
    }

    /** 批量填充账户名与分类名，避免 N+1 */
    private java.util.List<TransactionVo> enrich(java.util.List<TransactionRecord> list) {
        java.util.Set<Long> accIds = new java.util.HashSet<>();
        java.util.Set<Long> catIds = new java.util.HashSet<>();
        for (TransactionRecord r : list) {
            if (r.getFromAccountId() != null) accIds.add(r.getFromAccountId());
            if (r.getToAccountId() != null) accIds.add(r.getToAccountId());
            if (r.getCategoryId() != null) catIds.add(r.getCategoryId());
        }
        java.util.Map<Long, Account> accMap = accIds.isEmpty() ? java.util.Map.of()
                : accountMapper.selectBatchIds(accIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Account::getId, a -> a));
        java.util.Map<Long, Category> catMap = catIds.isEmpty() ? java.util.Map.of()
                : categoryMapper.selectBatchIds(catIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Category::getId, c -> c));
        java.util.List<TransactionVo> vos = new java.util.ArrayList<>(list.size());
        for (TransactionRecord r : list) {
            TransactionVo v = new TransactionVo();
            v.setId(r.getId());
            v.setType(r.getType());
            v.setFromAccountId(r.getFromAccountId());
            v.setToAccountId(r.getToAccountId());
            Account fa = r.getFromAccountId() != null ? accMap.get(r.getFromAccountId()) : null;
            Account ta = r.getToAccountId() != null ? accMap.get(r.getToAccountId()) : null;
            v.setFromAccountName(fa != null ? fa.getName() : null);
            v.setToAccountName(ta != null ? ta.getName() : null);
            v.setAmount(r.getAmount());
            v.setCategoryId(r.getCategoryId());
            Category c = r.getCategoryId() != null ? catMap.get(r.getCategoryId()) : null;
            v.setCategoryName(c != null ? c.getName() : null);
            v.setOccurredAt(r.getOccurredAt());
            v.setCounterparty(r.getCounterparty());
            v.setItem(r.getItem());
            v.setSource(r.getSource());
            v.setExternalId(r.getExternalId());
            v.setImportBatchId(r.getImportBatchId());
            vos.add(v);
        }
        return vos;
    }

    public PageResult<TransactionVo> page(Long userId, TransactionQuery q) {
        long pageNo = Math.max(q.getPage(), 1);
        long size = Math.min(Math.max(q.getSize(), 1), 100);
        var wrapper = buildWrapper(userId, q);
        wrapper.orderByDesc(TransactionRecord::getOccurredAt).orderByDesc(TransactionRecord::getId);
        Page<TransactionRecord> p = page(new Page<>(pageNo, size), wrapper);
        return new PageResult<>(p.getTotal(), enrich(p.getRecords()));
    }
```

（文件头补充 import：`com.balancewise.common.PageResult`、`com.balancewise.record.vo.TransactionVo`、`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`、`com.baomidou.mybatisplus.extension.plugins.pagination.Page`、`java.util.List` 等；上面为清楚用了全限定名的行可替换为普通 import——实现者自行统一即可，但不得改变行为。）

- [ ] **Step 4: 跑测试确认通过并提交**

Run: `mvn test`
Expected: 43/43 通过

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 流水分页查询（时间/类型/账户/分类/关键字筛选 + 名称填充）"
```

---

### Task 5: CSV 导出

**Files:**
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`
- Test: `backend/src/test/java/com/balancewise/record/TransactionServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 4 的 `buildWrapper`/`enrich`。
- Produces: `TransactionService.exportCsv(Long userId, TransactionQuery q)` 返回带 BOM 的 CSV 字符串（Task 6  controller 直接写出）。

- [ ] **Step 1: 写失败测试（追加）**

```java
    @Test
    void exportCsvContainsBomAndRows() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Category food = cat("餐饮", "expense", null);
        TransactionRequest r = req("expense", a.getId(), null, "25.50", food.getId());
        r.setCounterparty("麦当劳");
        r.setItem("午餐");
        transactionService.create(1L, r);

        String csv = transactionService.exportCsv(1L, new TransactionQuery());
        assertTrue(csv.startsWith("\uFEFF")); // Excel 中文 BOM
        assertTrue(csv.contains("类型,分类,账户,金额,时间,对方,备注,来源,批次号"));
        assertTrue(csv.contains("支出"));
        assertTrue(csv.contains("餐饮"));
        assertTrue(csv.contains("卡A"));
        assertTrue(csv.contains("25.50"));
        assertTrue(csv.contains("麦当劳"));
    }

    @Test
    void exportCsvRespectsFiltersAndExcludesDeleted() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        TransactionRecord del = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", null));
        transactionService.delete(1L, del.getId());
        transactionService.create(1L, req("income", null, b.getId(), "100.00", null));

        TransactionQuery q = new TransactionQuery();
        q.setType("income");
        String csv = transactionService.exportCsv(1L, q);
        assertTrue(csv.contains("收入"));
        assertFalse(csv.contains("支出"));
    }
```

- [ ] **Step 2: 实现 exportCsv（追加到 TransactionService）**

```java
    private static final java.util.Map<String, String> TYPE_LABEL = java.util.Map.of(
            "expense", "支出", "income", "收入", "transfer", "转账");
    private static final java.util.Map<String, String> SOURCE_LABEL = java.util.Map.of(
            "manual", "手工", "alipay", "支付宝", "wechat", "微信", "reconcile", "余额校正");

    /** CSV 字段转义：含逗号/引号/换行则加引号并翻倍引号 */
    private String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 按当前筛选条件导出 CSV（UC-3.6）。字段：类型,分类,账户,金额,时间,对方,备注,来源,批次号。
     * 带 UTF-8 BOM 以便 Excel 直接打开中文。上限 50000 行。
     */
    public String exportCsv(Long userId, TransactionQuery q) {
        var wrapper = buildWrapper(userId, q);
        wrapper.orderByDesc(TransactionRecord::getOccurredAt).orderByDesc(TransactionRecord::getId);
        wrapper.last("LIMIT 50000");
        java.util.List<TransactionRecord> list = list(wrapper);
        if (list.size() >= 50000) {
            throw new BizException("导出数据超过 50000 行，请缩小筛选范围");
        }

        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("类型,分类,账户,金额,时间,对方,备注,来源,批次号\n");
        java.util.List<TransactionVo> vos = enrich(list);
        for (TransactionVo v : vos) {
            String acc = "transfer".equals(v.getType())
                    ? (v.getFromAccountName() != null ? v.getFromAccountName() : "")
                      + " → " + (v.getToAccountName() != null ? v.getToAccountName() : "")
                    : ("expense".equals(v.getType()) ? v.getFromAccountName() : v.getToAccountName());
            sb.append(csv(TYPE_LABEL.getOrDefault(v.getType(), v.getType()))).append(',')
              .append(csv(v.getCategoryName())).append(',')
              .append(csv(acc)).append(',')
              .append(v.getAmount().toPlainString()).append(',')
              .append(v.getOccurredAt() != null ? v.getOccurredAt().format(fmt) : "").append(',')
              .append(csv(v.getCounterparty())).append(',')
              .append(csv(v.getItem())).append(',')
              .append(csv(SOURCE_LABEL.getOrDefault(v.getSource(), v.getSource()))).append(',')
              .append(v.getImportBatchId() != null ? String.valueOf(v.getImportBatchId()) : "")
              .append('\n');
        }
        return sb.toString();
    }
```

注意：`wrapper.last("LIMIT 50000")` 在 MySQL 与 H2(MySQL 模式) 均合法。`enrich` 在 Task 4 是 `private`——实现时把 `enrich` 改为 `protected`，否则 Task 5 无法复用。

- [ ] **Step 3: 跑测试确认通过并提交**

Run: `mvn test`
Expected: 45/45 通过

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 流水 CSV 导出（按筛选条件，UC-3.6）"
```

---

### Task 6: TransactionController + 接口集成测试

**Files:**
- Create: `backend/src/main/java/com/balancewise/record/controller/TransactionController.java`
- Test: `backend/src/test/java/com/balancewise/record/TransactionApiTest.java`

**Interfaces:**
- Consumes: Task 2-5 的 Service 全部公开方法；`@CurrentUser`、`Result`、`AuthApiTest` 的 @Import 模式。
- Produces: REST 端点 `GET/POST /api/transactions`、`PUT/DELETE /api/transactions/{id}`、`GET /api/transactions/export`（规格 §6 流水节）。

- [ ] **Step 1: 写集成测试（先写，参照 AuthApiTest 的 @SpringBootTest+MockMvc+@Import 模式）**

```java
package com.balancewise.record;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransactionApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long assetId;
    private String token;

    private void prepare() {
        token = jwtUtil.generate(1L);
        AccountRequest r = new AccountRequest();
        r.setName("工资卡");
        r.setType("asset");
        r.setSubType("debit_card");
        r.setInitialBalance(new BigDecimal("5000.00"));
        r.setRemark("");
        assetId = accountService.create(1L, r).getId();
    }

    private String expenseJson(String amount) {
        return "{\"type\":\"expense\",\"fromAccountId\":" + assetId
                + ",\"amount\":" + amount + ",\"occurredAt\":\"2026-10-01T12:00:00\"}";
    }

    @Test
    void fullLifecycleCreateListEditDelete() throws Exception {
        prepare();
        // 记一笔
        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("200.00")))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertTrue(id > 0);

        // 列表
        MvcResult listed = mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(listed.getResponse().getContentAsString()).path("data");
        assertEquals(1, data.path("total").asLong());
        assertEquals("工资卡", data.path("records").get(0).path("fromAccountName").asText());

        // 编辑金额
        mockMvc.perform(put("/api/transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("300.00")))
                .andExpect(status().isOk());

        // 删除
        mockMvc.perform(delete("/api/transactions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 列表为空，余额恢复
        MvcResult after = mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(0, objectMapper.readTree(after.getResponse().getContentAsString())
                .path("data").path("total").asLong());
        assertEquals(0, new BigDecimal("5000.00")
                .compareTo(accountService.getById(assetId).getBalance()));
    }

    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().is(401));
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"expense\",\"amount\":1}"))
                .andExpect(status().is(401));
    }

    @Test
    void invalidAmountReturns400() throws Exception {
        prepare();
        MvcResult res = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("0")))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void exportReturnsCsv() throws Exception {
        prepare();
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("88.00")))
                .andExpect(status().isOk());
        MvcResult res = mockMvc.perform(get("/api/transactions/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.startsWith("\uFEFF"));
        assertTrue(body.contains("支出"));
        assertTrue(body.contains("88.00"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=TransactionApiTest`
Expected: 404/编译相关失败（controller 不存在）

- [ ] **Step 3: 实现 TransactionController**

```java
package com.balancewise.record.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.PageResult;
import com.balancewise.common.Result;
import com.balancewise.record.dto.TransactionQuery;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.service.TransactionService;
import com.balancewise.record.vo.TransactionVo;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public Result<PageResult<TransactionVo>> list(@CurrentUser Long userId, TransactionQuery q) {
        return Result.ok(transactionService.page(userId, q));
    }

    @PostMapping
    public Result<TransactionRecord> create(@CurrentUser Long userId,
                                            @Valid @RequestBody TransactionRequest req) {
        return Result.ok(transactionService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<TransactionRecord> update(@CurrentUser Long userId, @PathVariable Long id,
                                            @Valid @RequestBody TransactionRequest req) {
        return Result.ok(transactionService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        transactionService.delete(userId, id);
        return Result.ok();
    }

    @GetMapping("/export")
    public void export(@CurrentUser Long userId, TransactionQuery q,
                       HttpServletResponse response) throws IOException {
        String csv = transactionService.exportCsv(userId, q);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=transactions.csv");
        response.getWriter().write(csv);
    }
}
```

注意：`GET /api/transactions/export` 与 `@GetMapping`（无路径变量）不冲突；Spring 精确路径优先。`TransactionQuery` 为 POJO，Spring 自动按 query param 绑定（`start`/`end` 用 `yyyy-MM-dd`，需确认 Jackson 全局支持 `LocalDate` query 绑定——Spring 默认 `FormattingConversionService` 支持 ISO `LocalDate`，无需额外配置）。

- [ ] **Step 4: 跑全部测试并提交**

Run: `mvn test`
Expected: 49/49 通过

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 流水 REST 接口与 MockMvc 集成测试"
```

---

### Task 7: 前端记一笔页

**Files:**
- Create: `frontend/src/views/RecordView.vue`
- Modify: `frontend/src/router/index.js:12`

**Interfaces:**
- Consumes: `GET /api/accounts`（`AccountSummaryVo.accounts`，Task 5 P1）、`GET /api/categories?kind=`（分类树，P1）、`POST /api/transactions`（Task 6）。
- Produces: 路由 `/record` 指向 RecordView；保存成功后跳 `/transactions`。

- [ ] **Step 1: 改路由**

`frontend/src/router/index.js` 中把 record 一行改为（其余行不动）：

```js
      { path: 'record', component: () => import('../views/RecordView.vue'), meta: { title: '记一笔' } },
```

- [ ] **Step 2: 写 RecordView.vue**

```vue
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
```

- [ ] **Step 3: 构建验证并提交**

Run: `cd frontend && npm run build`
Expected: 构建成功

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 前端记一笔页（三类型/级联分类/本地时间序列化）"
```

---

### Task 8: 前端流水页

**Files:**
- Create: `frontend/src/views/TransactionsView.vue`
- Modify: `frontend/src/router/index.js:13`

**Interfaces:**
- Consumes: Task 6 全部流水端点；Task 7 的 `fmtLocal` 思路（本文件内重复实现一次，两视图独立）。
- Produces: 路由 `/transactions` 指向 TransactionsView。

- [ ] **Step 1: 改路由**

```js
      { path: 'transactions', component: () => import('../views/TransactionsView.vue'), meta: { title: '流水' } },
```

- [ ] **Step 2: 写 TransactionsView.vue**

```vue
<template>
  <div>
    <el-card style="margin-bottom: 16px">
      <div class="filters">
        <el-date-picker
          v-model="range" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px"
        />
        <el-select v-model="query.type" placeholder="类型" clearable style="width: 110px">
          <el-option label="支出" value="expense" />
          <el-option label="收入" value="income" />
          <el-option label="转账" value="transfer" />
        </el-select>
        <el-select v-model="query.accountId" placeholder="账户" clearable filterable style="width: 150px">
          <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
        </el-select>
        <el-cascader
          v-model="query.categoryId" :options="categoryOptions" :props="cascaderProps"
          filterable clearable placeholder="分类" style="width: 170px"
        />
        <el-input v-model="query.keyword" placeholder="对方/备注关键字" clearable style="width: 180px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-button @click="exportCsv">导出 CSV</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <el-table-column prop="occurredAt" label="时间" width="165" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.type)">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ row.categoryName || '—' }}</template>
        </el-table-column>
        <el-table-column label="账户" min-width="150">
          <template #default="{ row }">{{ accountText(row) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <span :class="amountClass(row)">{{ amountText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="counterparty" label="对方" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterparty || '—' }}</template>
        </el-table-column>
        <el-table-column prop="item" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.item || '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无流水" :image-size="60" /></template>
      </el-table>
      <el-pagination
        v-model:current-page="query.page" v-model:page-size="query.size"
        :total="total" :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load" @size-change="search"
      />
    </el-card>

    <el-dialog v-model="dlg.visible" title="编辑流水" width="520px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="类型">
          <el-radio-group v-model="dlg.form.type">
            <el-radio-button value="expense">支出</el-radio-button>
            <el-radio-button value="income">收入</el-radio-button>
            <el-radio-button value="transfer">转账</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="dlg.form.type !== 'income'" :label="dlg.form.type === 'transfer' ? '转出账户' : '账户'">
          <el-select v-model="dlg.form.fromAccountId" filterable style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dlg.form.type !== 'expense'" :label="dlg.form.type === 'transfer' ? '转入账户' : '账户'">
          <el-select v-model="dlg.form.toAccountId" filterable style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dlg.form.type !== 'transfer'" label="分类">
          <el-cascader
            v-model="dlg.form.categoryId" :options="categoryOptions" :props="cascaderProps"
            filterable clearable placeholder="未分类" style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="dlg.form.amount" :min="0.01" :max="99999999" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="dlg.form.occurredAt" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item label="对方">
          <el-input v-model="dlg.form.counterparty" maxlength="128" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dlg.form.item" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const range = ref(null)
const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: false }

const query = reactive({ page: 1, size: 20, type: null, accountId: null, categoryId: null, keyword: '' })
const categoryOptions = computed(() => {
  if (query.type === 'income') return incomeTree.value
  if (query.type === 'expense') return expenseTree.value
  return [...expenseTree.value, ...incomeTree.value]
})

const TYPE_LABEL = { expense: '支出', income: '收入', transfer: '转账' }
const SOURCE_LABEL = { manual: '手工', alipay: '支付宝', wechat: '微信', reconcile: '校正' }

const typeLabel = t => TYPE_LABEL[t] || t
const tagType = t => (t === 'expense' ? 'danger' : t === 'income' ? 'success' : 'info')
const sourceLabel = s => SOURCE_LABEL[s] || s || '—'
const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const accountText = r =>
  r.type === 'transfer'
    ? `${r.fromAccountName || ''} → ${r.toAccountName || ''}`
    : r.type === 'expense' ? (r.fromAccountName || '—') : (r.toAccountName || '—')
const amountText = r =>
  r.type === 'expense' ? `-¥${money(r.amount)}`
  : r.type === 'income' ? `+¥${money(r.amount)}` : `¥${money(r.amount)}`
const amountClass = r => (r.type === 'expense' ? 'amt-expense' : r.type === 'income' ? 'amt-income' : '')

onMounted(async () => {
  const [acc, exp, inc] = await Promise.all([
    request.get('/accounts'),
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  accounts.value = acc.data.accounts
  expenseTree.value = exp.data
  incomeTree.value = inc.data
  await load()
})

function params() {
  return {
    page: query.page, size: query.size,
    type: query.type || undefined,
    accountId: query.accountId || undefined,
    categoryId: query.categoryId || undefined,
    keyword: query.keyword || undefined,
    start: range.value?.[0], end: range.value?.[1],
  }
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/transactions', { params: params() })
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetFilters() {
  range.value = null
  Object.assign(query, { page: 1, type: null, accountId: null, categoryId: null, keyword: '' })
  load()
}

// Date → 本地 'yyyy-MM-ddTHH:mm:ss'
function fmtLocal(d) {
  if (!d) return null
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const dlg = reactive({
  visible: false, saving: false, id: null,
  form: { type: 'expense', fromAccountId: null, toAccountId: null, categoryId: null,
          amount: null, occurredAt: null, counterparty: '', item: '' },
})

function openEdit(row) {
  Object.assign(dlg, {
    id: row.id, visible: true,
    form: {
      type: row.type,
      fromAccountId: row.fromAccountId,
      toAccountId: row.toAccountId,
      categoryId: row.categoryId,
      amount: Number(row.amount),
      occurredAt: row.occurredAt ? new Date(row.occurredAt) : null,
      counterparty: row.counterparty || '',
      item: row.item || '',
    },
  })
}

async function saveEdit() {
  const f = dlg.form
  dlg.saving = true
  try {
    await request.put(`/transactions/${dlg.id}`, {
      type: f.type, amount: f.amount,
      occurredAt: fmtLocal(f.occurredAt),
      counterparty: f.counterparty || null, item: f.item || null,
      categoryId: f.type === 'transfer' ? null : f.categoryId,
      fromAccountId: f.type === 'income' ? null : f.fromAccountId,
      toAccountId: f.type === 'expense' ? null : f.toAccountId,
    })
    ElMessage.success('已保存')
    dlg.visible = false
    await load()
  } finally {
    dlg.saving = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除该笔${typeLabel(row.type)}（${amountText(row)}）？删除后余额将自动冲销，流水保留可追溯。`,
      '删除确认', { type: 'warning' })
  } catch { return }
  await request.delete(`/transactions/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

async function exportCsv() {
  const res = await request.get('/transactions/export', { params: params(), responseType: 'blob' })
  const url = URL.createObjectURL(new Blob([res], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `transactions_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.amt-expense { color: #e8604c; font-weight: 600; font-variant-numeric: tabular-nums; }
.amt-income { color: #3aa97a; font-weight: 600; font-variant-numeric: tabular-nums; }
</style>
```

- [ ] **Step 3: 构建验证并提交**

Run: `cd frontend && npm run build`
Expected: 构建成功

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p2): 前端流水页（筛选/分页/编辑/删除/导出）"
```

---

### Task 9: P2 端到端验证与阶段收尾

**Files:**
- Modify: `README.md`（功能清单小节更新）

- [ ] **Step 1: 后端全量回归**

Run: `cd backend && mvn test`
Expected: 49/49 通过（JwtUtil 3 + AuthApi 5 + User 4 + Account 7 + Category 5 + TransactionService 16 + TransactionApi 4，数字以实际类为准）

- [ ] **Step 2: 端到端验证（curl + 真实 MySQL，用户 tester01/123456）**

后端后台启动（powershell Start-Process 分离，日志到 `logs/backend-p2-e2e.log`），MySQL 已在后台。依次验证并记录：

1. 登录拿 token。
2. 建两个资产账户（A 10000、B 0）+ 一个负债账户（花呗 欠款 500）。
3. 支出：A 花 200 → A 余额 9800。
4. 收入：B 收 3500 → B 余额 3500。
5. 转账：A → 花呗 500（还款）→ A 9300、花呗 0（欠款还清，负债归 0）。
6. 负债消费：花呗 花呗不可用（已还清欠款但账户未归档）——用花呗支出 100 → 余额 -100。
7. 编辑第 3 步的流水：金额 200→300 → A 余额 9200；再把账户从 A 改到 B → A 9500、B 3200。
8. 删除第 5 步转账流水 → A 加回 500、花呗减 500 → A 10000？（按第 7 步编辑后的状态算，实现者按实际链路记录每步余额断言）。
9. `GET /api/transactions?type=expense` 筛选、`?keyword=` 关键字、分页参数生效。
10. `GET /api/transactions/export` 返回带 BOM 的 CSV，含「支出/收入/转账」行。
11. MySQL 查库核对：流水数、`account.balance` 与接口一致、`is_deleted` 正确。
12. 验收数据清理：删除本次造的测试账户与流水（先删流水再删账户，或直接用测试用户整体清理）。

- [ ] **Step 3: 前端构建**

Run: `cd frontend && npm run build`
Expected: 构建成功

- [ ] **Step 4: 更新 README 功能清单**

在 README「项目文档」小节前追加：

```markdown
## 当前功能
- 用户：注册（自动初始化 39 个预置分类 + 现金账户）/ 登录（JWT）
- 账户：新建（资产/负债）、编辑、归档、删除（有交易拦截）、净资产汇总
- 分类：两级树、预置/自定义、改名、三类删除拦截（预置/有子类/被引用）
- 记账：支出/收入/转账（余额联动，负债余额为负）、流水筛选分页、编辑（冲销+入账）、删除（软删+冲销）、CSV 导出
```

- [ ] **Step 5: 提交 + 打 tag**

```bash
git add README.md
git -c user.name="李东升" -c user.email="lids@local" commit -m "docs(p2): README 功能清单更新"
git tag p2-ledger-v1
```

（分支名 `p2-ledger` 与 tag `p2-ledger-v1` 不同名，避免 P1 的 ref 歧义问题。）

- [ ] **Step 6: 回收进程并汇报**

后端验证进程回收（不杀 MySQL）。向项目所有者汇报：测试通过数、e2e 各步骤余额断言摘要、tag 位置，等待阶段评审；评审通过后编写 P3（账单导入）计划。

---

## Self-Review 记录

- **规格覆盖**：UC-3.1/3.2（Task 2）、UC-3.3（Task 4/8）、UC-3.4（Task 3/8）、UC-3.5（Task 3/8）、UC-3.6（Task 5/8）；§7.1（Task 2）、§7.2（Task 3）、§7.3（Task 3）；规格 §6 流水端点（Task 6）。对账（§7.6）与导入（§7.4/7.5）属 P3/P5，不在本计划。
- **占位符扫描**：无 TBD/“适当处理”类描述；所有代码步骤均含完整代码。
- **类型一致性**：`TransactionRequest`/`TransactionQuery`/`TransactionVo`/`PageResult` 字段名跨任务已核对一致；`applyBalance(from, to, amount)` 语义（from 减、to 加）在 Task 2 定义、Task 3 反向冲销以 `amount.negate()` 调用，已核对方向正确（冲销后 from 加回、to 减回）。
- **已知计划内简化**：分类筛选为叶子分类精确匹配（规格未要求父分类下钻，P4 统计再展开）；导出行上限 50000。
