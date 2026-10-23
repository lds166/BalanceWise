# P3 账单导入模块实施计划（解析 / 批次 / 重复检测 / 撤销）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现账单导入模块（UC-4.1 ~ UC-4.11）：上传支付宝/微信 CSV → 解析清洗 → 类型识别 → 分类自动映射 → 账户匹配 → 重复检测 → 不落库预览（可改分类/账户/勾选）→ 单事务批次入账 → 批次列表/明细 → 幂等批次撤销。顺手清偿 P2 终审分诊的遗留 Minor。

**Architecture:** 解析层 `BillParser` 接口 + 平台实现（支付宝/微信），输出标准化 `ParsedRow`；`ImportService` 编排预览（纯读）与确认（单事务：import_batch + 流水批量插入 + 复用 `TransactionService.applyBalance` 逐条余额联动——**绝不复制余额逻辑**）；撤销按流水当前值反向冲销。重复检测应用层实现（§5.4 规则 4），不设唯一索引。

**Tech Stack:** Spring Boot 3.3（multipart 上传）+ MyBatis-Plus + JUnit 5 + MockMvc；Vue 3 + Element Plus（el-upload/el-tabs）。

## Global Constraints

- JDK 17：`C:\Program Files\Java\jdk-17`（每个 Bash 调用重新 `export JAVA_HOME`）
- Maven：`D:\KimiData\kimi\Workspaces\BalanceWise\.tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\KimiData\kimi\Workspaces\BalanceWise\.tools\maven-settings.xml`
- git 提交身份：`-c user.name="李东升" -c user.email="lids@local"`
- 统一返回 `Result{code,message,data}`；code=0 成功、400 业务错误、401 未登录
- **余额规则**：负债余额为负；任何流水 = from 减 / to 加；余额更新唯一入口 `AccountMapper.addBalance`；账务写操作必须 `@Transactional` 且复用 `TransactionService.applyBalance`，禁止在新代码中重写余额增减
- 流水 source 枚举：`manual / alipay / wechat / reconcile`（写入值必须落在此集合，前端与导出标签依赖）
- 重复检测不设数据库唯一索引（§5.4 规则 4）；只比对 `is_deleted=0` 的同源记录 + 批内记录
- 导入流水必须保留 `external_id` 与 `import_batch_id`（溯源）；编辑不丢（P2 已保证）
- 测试约定：`@SpringBootTest @ActiveProfiles("test")` + 类级 `@Transactional`；BigDecimal 比较用 compareTo
- 表结构不再改（import_batch 与 transaction_record 表 P1 已建好）
- Git Bash 内嵌中文 JSON 会 GBK 乱码：含中文请求体写 UTF-8 文件 `curl -d @文件`；样例 CSV 用 UTF-8
- Bash 不支持后台任务：后台进程用 `powershell Start-Process` 分离，验证完回收
- 阶段分支：`p3-import`（从 main 切出）；阶段 tag：`p3-import-v1`
- 文件上传上限 10 MB（`spring.servlet.multipart.max-file-size=10MB`）

---

### Task 1: P2 遗留修复 + 建分支

**Files:**
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`（exportCsv 上限、buildWrapper 时间边界、csv 转义）
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`（delete 幂等）
- Test: `backend/src/test/java/com/balancewise/record/TransactionServiceTest.java`（追加）
- Test: `backend/src/test/java/com/balancewise/account/AccountApiTest.java`（新建）

**Interfaces:**
- Consumes: P2 既有 TransactionService/AccountService/TransactionApiTest 模式。
- Produces: 分支 `p3-import`；导出上限缺陷修复；删除流水幂等；删除保护 MockMvc 集成测试。

- [ ] **Step 1: 建分支**

```bash
cd D:\KimiData\kimi\Workspaces\BalanceWise
git checkout main && git checkout -b p3-import
```

- [ ] **Step 2: 写失败测试（TransactionServiceTest 追加）**

```java
    @Test
    void exportCsvGuardsFormulaInjectionAndCarriageReturn() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRequest r = req("expense", a.getId(), null, "10.00", null);
        r.setCounterparty("=cmd|'/c calc'!A1");   // Excel 公式注入样本
        r.setItem("含\r裸回车");
        transactionService.create(1L, r);

        String csv = transactionService.exportCsv(1L, new TransactionQuery());
        // 公式注入：以 ' 前缀中和
        assertTrue(csv.contains("'=cmd"));
        // 裸 \r 触发引号包裹
        assertTrue(csv.contains("\"含\r裸回车\""));
    }

    @Test
    void endDateFilterIncludesWholeDayExclusively() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRequest r1 = req("expense", a.getId(), null, "10.00", null);
        r1.setOccurredAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        transactionService.create(1L, r1);
        TransactionRequest r2 = req("expense", a.getId(), null, "20.00", null);
        r2.setOccurredAt(LocalDateTime.of(2026, 11, 1, 0, 0, 0));
        transactionService.create(1L, r2);

        TransactionQuery q = new TransactionQuery();
        q.setStart(java.time.LocalDate.of(2026, 10, 1));
        q.setEnd(java.time.LocalDate.of(2026, 10, 31));
        // 新语义：end 含当天 23:59:59.xxx 全部（< 次日 00:00）
        assertEquals(1, transactionService.page(1L, q).getTotal());
    }

    @Test
    void doubleDeleteRejected() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", null));
        transactionService.delete(1L, r.getId());
        BizException e = assertThrows(BizException.class,
                () -> transactionService.delete(1L, r.getId()));
        assertEquals("流水不存在", e.getMessage());
    }
```

注意：Step 2 先跑测试时必须红（formula 注入与 `\r` 转义断言在 P2 实现下必失败），确认红灯后再进 Step 3；若绿灯说明实现已存在，属异常，停止并上报。

- [ ] **Step 3: 实现修复（TransactionService 三处）**

(a) `buildWrapper` 的 end 条件改为排他上界：

```java
        if (q.getEnd() != null) {
            // 排他上界：含 end 当天全部（亚秒缝隙安全）
            w.lt(TransactionRecord::getOccurredAt, q.getEnd().plusDays(1).atStartOfDay());
        }
```

(b) `exportCsv` 的上限判定改为 50001：

```java
        wrapper.last("LIMIT 50001");
        java.util.List<TransactionRecord> list = list(wrapper);
        if (list.size() > 50000) {
            throw new BizException("导出数据超过 50000 行，请缩小筛选范围");
        }
```

(c) `csv()` 转义增加裸 `\r` 与公式注入防护：

```java
    /** CSV 字段转义：含逗号/引号/换行则加引号并翻倍引号；以 = + - @ 开头加 ' 前缀防公式注入 */
    private String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
```

(d) `delete` 改为条件更新（并发双击不双重冲销）：

```java
    @Transactional
    public void delete(Long userId, Long id) {
        TransactionRecord r = owned(userId, id);
        // 条件更新抢占：并发双击时第二个请求影响 0 行，直接拒绝，不会双重冲销
        int updated = getBaseMapper().update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TransactionRecord>()
                .eq(TransactionRecord::getId, r.getId())
                .eq(TransactionRecord::getIsDeleted, 0)
                .set(TransactionRecord::getIsDeleted, 1));
        if (updated == 0) {
            throw new BizException("流水不存在或已删除");
        }
        applyBalance(r.getFromAccountId(), r.getToAccountId(), r.getAmount().negate());
    }
```

（文件头如需新增 `LambdaUpdateWrapper` 的 import 则补。）

- [ ] **Step 4: AccountApiTest（新建，删除保护集成测试）**

```java
package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountApiTest {

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

    @Test
    void deleteProtectedWhenTransactionsExist() throws Exception {
        String token = jwtUtil.generate(1L);
        AccountRequest r = new AccountRequest();
        r.setName("测试卡");
        r.setType("asset");
        r.setSubType("debit_card");
        r.setInitialBalance(new BigDecimal("100.00"));
        r.setRemark("");
        Long accountId = accountService.create(1L, r).getId();

        TransactionRequest t = new TransactionRequest();
        t.setType("expense");
        t.setFromAccountId(accountId);
        t.setAmount(new BigDecimal("10.00"));
        transactionService.create(1L, t);

        MvcResult res = mockMvc.perform(delete("/api/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400, objectMapper.readTree(body).path("code").asInt());
        assertTrue(body.contains("无法删除"));
    }
}
```

- [ ] **Step 5: 回归并提交**

Run: `cd backend && mvn test`
Expected: 53/53 通过（49 既有 + 3 TransactionServiceTest 新增 + 1 AccountApiTest）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "fix(p3): P2 遗留——导出上限/时间边界/CSV公式注入/删除幂等/删除保护集成测试"
```

---

### Task 2: BillParser 接口 + 支付宝解析器 + 样例

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/parser/BillParser.java`
- Create: `backend/src/main/java/com/balancewise/importbill/parser/ParsedRow.java`
- Create: `backend/src/main/java/com/balancewise/importbill/parser/BillParseResult.java`
- Create: `backend/src/main/java/com/balancewise/importbill/parser/BillParserSupport.java`
- Create: `backend/src/main/java/com/balancewise/importbill/parser/AlipayParser.java`
- Create: `backend/src/test/resources/fixtures/alipay-sample.csv`
- Test: `backend/src/test/java/com/balancewise/importbill/AlipayParserTest.java`

**Interfaces:**
- Consumes: 无（纯新增）。
- Produces: `BillParser{String platform(); BillParseResult parse(byte[] content);}`；`ParsedRow{lineNo,externalId,type,amount,occurredAt,counterparty,item,platformCategory,payMethod}`；`BillParseResult{List<ParsedRow> rows, List<String> dropped}`（Task 3/5 复用）。

- [ ] **Step 1: 样例文件**（UTF-8，P1 既有 39 分类之外的自造脱敏数据）

`backend/src/test/resources/fixtures/alipay-sample.csv`：

```csv
支付宝交易记录明细查询
账号：138****0000
起始时间：[2026-09-01 00:00:00]    终止时间：[2026-09-30 23:59:59]
----------------------------------------
交易号,交易创建时间,类型,对方,商品名称,金额（元）,收/支,状态,付款方式,备注
202609010000100001,2026-09-01 08:30:22,交易付款,永辉超市,生鲜食品,35.50,支出,交易成功,余额宝,
202609020000100002,2026-09-02 12:00:00,交易付款,美团外卖,外卖午餐,25.00,支出,交易成功,花呗,
202609030000100003,2026-09-03 10:00:00,余额宝转入,余额宝,余额宝-转入,1000.00,支出,交易成功,余额,
202609040000100004,2026-09-04 09:00:00,退款,永辉超市,生鲜退款,35.50,收入,交易成功,余额宝,
202609050000100005,2026-09-05 20:00:00,交易付款,某商户,"iPad, 保护壳",99.99,支出,交易关闭,余额宝,
```

（第 6 行商品名含英文逗号且带引号，验证 CSV 引号解析；第 5 行交易关闭应被丢弃。）

- [ ] **Step 2: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.importbill.parser.AlipayParser;
import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.ParsedRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlipayParserTest {

    private final AlipayParser parser = new AlipayParser();

    @Test
    void parsesValidRowsAndDropsFailedTransactions() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        BillParseResult result = parser.parse(content);

        // 5 行数据，交易关闭 1 行被丢弃
        assertEquals(4, result.getRows().size());
        assertEquals(1, result.getDropped().size());
        assertTrue(result.getDropped().get(0).contains("交易关闭"));

        ParsedRow first = result.getRows().get(0);
        assertEquals("202609010000100001", first.getExternalId());
        assertEquals("expense", first.getType());
        assertEquals(0, new BigDecimal("35.50").compareTo(first.getAmount()));
        assertEquals("永辉超市", first.getCounterparty());
        assertEquals("生鲜食品", first.getItem());
        assertEquals("余额宝", first.getPayMethod());
        assertEquals("交易付款", first.getPlatformCategory());
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 30, 22), first.getOccurredAt());
    }

    @Test
    void quotedFieldWithCommaParsedAsSingleCell() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ParsedRow quoted = parser.parse(content).getRows().stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("iPad"))
                .findFirst().orElseThrow();
        assertEquals("iPad, 保护壳", quoted.getItem());
    }

    @Test
    void typeRecognition() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        List<ParsedRow> rows = parser.parse(content).getRows();
        // 交易付款→支出(expense)，退款→收入(income)，余额宝转入→转账(transfer)
        assertEquals("expense", rows.get(0).getType());
        assertEquals("income", rows.get(3).getType());
        assertEquals("transfer", rows.get(2).getType());
    }
}
```

- [ ] **Step 3: 实现四个类 + 支付宝解析器**

`ParsedRow.java`：

```java
package com.balancewise.importbill.parser;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 解析后的标准化账单行（与平台无关） */
@Data
public class ParsedRow {
    private int lineNo;
    private String externalId;
    /** expense / income / transfer */
    private String type;
    private BigDecimal amount;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    /** 平台侧分类字段（支付宝=类型，微信=交易类型），用于分类映射 */
    private String platformCategory;
    /** 付款方式，用于账户匹配 */
    private String payMethod;
}
```

`BillParseResult.java`：

```java
package com.balancewise.importbill.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BillParseResult {
    private List<ParsedRow> rows = new ArrayList<>();
    /** 被丢弃行的原因描述，如 "第6行：状态=交易关闭" */
    private List<String> dropped = new ArrayList<>();
}
```

`BillParser.java`：

```java
package com.balancewise.importbill.parser;

public interface BillParser {

    /** alipay / wechat，必须落在流水 source 枚举内 */
    String platform();

    /** 解析账单文件字节内容（UTF-8 优先，失败回退 GBK），清洗后返回标准化行 */
    BillParseResult parse(byte[] content);
}
```

`BillParserSupport.java`（共用工具）：

```java
package com.balancewise.importbill.parser;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class BillParserSupport {

    public static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BillParserSupport() {
    }

    /** UTF-8 严格解码，失败回退 GBK（支付宝历史导出为 GBK） */
    public static String decode(byte[] content) {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = utf8.decode(ByteBuffer.wrap(content));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return new String(content, java.nio.charset.Charset.forName("GBK"));
        }
    }

    /** 按行切分（支持 \n 与 \r\n），去掉 BOM */
    public static List<String> lines(String text) {
        String clean = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<String> out = new ArrayList<>();
        for (String line : clean.split("\r?\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** CSV 行切分：支持 "..." 包裹、内嵌逗号与 "" 转义引号 */
    public static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
            } else if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /** 金额清洗：去 ¥ ￥ 元 千分位逗号与空白 */
    public static BigDecimal parseAmount(String raw) {
        String s = raw == null ? "" : raw
                .replace("¥", "").replace("￥", "").replace("元", "")
                .replace(",", "").trim();
        return new BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static LocalDateTime parseTime(String raw) {
        return LocalDateTime.parse(raw.trim(), DT);
    }
}
```

`AlipayParser.java`：

```java
package com.balancewise.importbill.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝账单 CSV 解析。
 * 表头前 4 行为说明（"支付宝交易记录明细查询"等），解析器按首行单元格 == "交易号" 定位表头。
 * 仅保留 状态=交易成功 的记录；类型识别：退款→收入，余额宝/转账类→转账，其余按 收/支。
 */
public class AlipayParser implements BillParser {

    private static final Map<String, String> TYPE_MAP = Map.of(
            "退款", "income",
            "余额宝转入", "transfer",
            "余额宝转出", "transfer",
            "转账", "transfer",
            "花呗还款", "transfer");

    @Override
    public String platform() {
        return "alipay";
    }

    @Override
    public BillParseResult parse(byte[] content) {
        BillParseResult result = new BillParseResult();
        List<String> lines = BillParserSupport.lines(BillParserSupport.decode(content));
        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("交易号".equals(BillParserSupport.splitCsvLine(lines.get(i)).get(0))) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new com.balancewise.common.BizException("文件格式与所选平台不符：未找到支付宝表头");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> headers = BillParserSupport.splitCsvLine(lines.get(headerIdx));
        for (int i = 0; i < headers.size(); i++) {
            col.put(headers.get(i), i);
        }
        requireColumns(col, "交易号", "交易创建时间", "类型", "对方", "商品名称", "金额（元）", "收/支", "状态", "付款方式");

        for (int i = headerIdx + 1; i < lines.size(); i++) {
            List<String> cells = BillParserSupport.splitCsvLine(lines.get(i));
            int lineNo = i + 1;
            String status = cell(cells, col, "状态");
            if (!"交易成功".equals(status)) {
                result.getDropped().add("第" + lineNo + "行：状态=" + (status.isEmpty() ? "空" : status));
                continue;
            }
            try {
                ParsedRow row = new ParsedRow();
                row.setLineNo(lineNo);
                row.setExternalId(cell(cells, col, "交易号"));
                row.setOccurredAt(BillParserSupport.parseTime(cell(cells, col, "交易创建时间")));
                row.setCounterparty(cell(cells, col, "对方"));
                row.setItem(cell(cells, col, "商品名称"));
                row.setAmount(BillParserSupport.parseAmount(cell(cells, col, "金额（元）")));
                row.setPlatformCategory(cell(cells, col, "类型"));
                row.setPayMethod(cell(cells, col, "付款方式"));
                row.setType(resolveType(cell(cells, col, "类型"), cell(cells, col, "收/支")));
                result.getRows().add(row);
            } catch (RuntimeException e) {
                result.getDropped().add("第" + lineNo + "行：解析失败（" + e.getMessage() + "）");
            }
        }
        return result;
    }

    private String resolveType(String category, String direction) {
        String mapped = TYPE_MAP.get(category);
        if (mapped != null) {
            return mapped;
        }
        return "收入".equals(direction) ? "income" : "expense";
    }

    private void requireColumns(Map<String, Integer> col, String... names) {
        for (String name : names) {
            if (!col.containsKey(name)) {
                throw new com.balancewise.common.BizException("文件格式与所选平台不符：缺少列「" + name + "」");
            }
        }
    }

    private String cell(List<String> cells, Map<String, Integer> col, String name) {
        int idx = col.get(name);
        return idx < cells.size() ? cells.get(idx) : "";
    }
}
```

- [ ] **Step 4: 跑测试确认通过并提交**

Run: `mvn test -Dtest=AlipayParserTest` → 通过后 `mvn test` 全量
Expected: 56/56（53 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 账单解析器接口与支付宝 CSV 解析（清洗/类型识别/引号与编码兼容）"
```

---

### Task 3: 微信解析器

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/parser/WechatParser.java`
- Create: `backend/src/test/resources/fixtures/wechat-sample.csv`
- Test: `backend/src/test/java/com/balancewise/importbill/WechatParserTest.java`

**Interfaces:**
- Consumes: Task 2 的 `BillParser`/`ParsedRow`/`BillParseResult`/`BillParserSupport`。
- Produces: `WechatParser`（platform() = "wechat"），Task 5 通过 `Map<String, BillParser>` 按 platform 选用。

- [ ] **Step 1: 样例文件**

`backend/src/test/resources/fixtures/wechat-sample.csv`（UTF-8）：

```csv
微信账单导出
微信号：wxid_demo
----------------------------------------
交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,状态,交易单号,商户单号,备注
2026-09-01 18:22:11,商户消费,美团平台,外卖晚餐,支出,￥45.50,零钱,支付成功,4200000001202609010001,,
2026-09-02 08:10:00,转账,张三,转账-收款,收入,￥200.00,零钱,支付成功,4200000002202609020002,,
2026-09-03 21:00:00,提现,零钱,提现至银行卡,支出,￥500.00,工商银行储蓄卡,支付失败,4200000003202609030003,,
2026-09-04 12:00:00,充值,工商银行,零钱-充值,收入,￥500.00,工商银行储蓄卡,支付成功,4200000004202609040004,,
```

（金额带 `￥` 前缀验证货币符号清洗；支付失败行被丢弃；充值/提现/转账识别为 transfer。）

- [ ] **Step 2: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.ParsedRow;
import com.balancewise.importbill.parser.WechatParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WechatParserTest {

    private final WechatParser parser = new WechatParser();

    @Test
    void parsesValidRowsAndStripsCurrencySymbol() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/wechat-sample.csv").getFile().toPath());
        BillParseResult result = parser.parse(content);

        assertEquals(3, result.getRows().size());
        assertEquals(1, result.getDropped().size());

        ParsedRow first = result.getRows().get(0);
        assertEquals("4200000001202609010001", first.getExternalId());
        assertEquals("expense", first.getType());
        assertEquals(0, new BigDecimal("45.50").compareTo(first.getAmount()));
        assertEquals("美团平台", first.getCounterparty());
        assertEquals("商户消费", first.getPlatformCategory());
        assertEquals("零钱", first.getPayMethod());
        assertEquals(LocalDateTime.of(2026, 9, 1, 18, 22, 11), first.getOccurredAt());
    }

    @Test
    void transferTypeRecognition() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/wechat-sample.csv").getFile().toPath());
        List<ParsedRow> rows = parser.parse(content).getRows();
        assertEquals("income", rows.get(1).getType());   // 转账收款 → transfer? 见下
        assertEquals("transfer", rows.get(2).getType()); // 充值 → transfer
    }
}
```

注意：上面第二个测试的第一行断言写的是 `"income"`，这是**故意的红灯**——按微信类型识别规则「转账 → transfer」，正确值应为 `"transfer"`。实现 Step 3 时**先把该断言改为 `"transfer"`** 再跑（规则见 Step 3 的 TYPE_MAP）。

- [ ] **Step 3: 实现 WechatParser**

```java
package com.balancewise.importbill.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信账单 CSV 解析。
 * 按首行单元格 == "交易时间" 定位表头；仅保留 状态=支付成功 的记录。
 * 类型识别：充值/提现/零钱通/转账类 → transfer；红包按收/支；其余消费类按收/支。
 */
public class WechatParser implements BillParser {

    private static final java.util.Set<String> TRANSFER_TYPES = java.util.Set.of(
            "转账", "充值", "提现", "零钱通转入", "零钱通转出", "零钱充值", "零钱提现");

    @Override
    public String platform() {
        return "wechat";
    }

    @Override
    public BillParseResult parse(byte[] content) {
        BillParseResult result = new BillParseResult();
        List<String> lines = BillParserSupport.lines(BillParserSupport.decode(content));
        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("交易时间".equals(BillParserSupport.splitCsvLine(lines.get(i)).get(0))) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new com.balancewise.common.BizException("文件格式与所选平台不符：未找到微信表头");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> headers = BillParserSupport.splitCsvLine(lines.get(headerIdx));
        for (int i = 0; i < headers.size(); i++) {
            col.put(headers.get(i), i);
        }
        requireColumns(col, "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "状态", "交易单号");

        for (int i = headerIdx + 1; i < lines.size(); i++) {
            List<String> cells = BillParserSupport.splitCsvLine(lines.get(i));
            int lineNo = i + 1;
            String status = cell(cells, col, "状态");
            if (!"支付成功".equals(status)) {
                result.getDropped().add("第" + lineNo + "行：状态=" + (status.isEmpty() ? "空" : status));
                continue;
            }
            try {
                ParsedRow row = new ParsedRow();
                row.setLineNo(lineNo);
                row.setExternalId(cell(cells, col, "交易单号"));
                row.setOccurredAt(BillParserSupport.parseTime(cell(cells, col, "交易时间")));
                row.setCounterparty(cell(cells, col, "交易对方"));
                row.setItem(cell(cells, col, "商品"));
                row.setAmount(BillParserSupport.parseAmount(cell(cells, col, "金额(元)")));
                row.setPlatformCategory(cell(cells, col, "交易类型"));
                row.setPayMethod(cell(cells, col, "支付方式"));
                row.setType(resolveType(cell(cells, col, "交易类型"), cell(cells, col, "收/支")));
                result.getRows().add(row);
            } catch (RuntimeException e) {
                result.getDropped().add("第" + lineNo + "行：解析失败（" + e.getMessage() + "）");
            }
        }
        return result;
    }

    private String resolveType(String tradeType, String direction) {
        if (TRANSFER_TYPES.contains(tradeType)) {
            return "transfer";
        }
        return "收入".equals(direction) ? "income" : "expense";
    }

    private void requireColumns(Map<String, Integer> col, String... names) {
        for (String name : names) {
            if (!col.containsKey(name)) {
                throw new com.balancewise.common.BizException("文件格式与所选平台不符：缺少列「" + name + "」");
            }
        }
    }

    private String cell(List<String> cells, Map<String, Integer> col, String name) {
        int idx = col.get(name);
        return idx < cells.size() ? cells.get(idx) : "";
    }
}
```

- [ ] **Step 4: 跑测试并提交**

Run: `mvn test`
Expected: 58/58（断言改正为 transfer 后全绿）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 微信账单 CSV 解析"
```

---

### Task 4: ImportBatch 实体 + 分类映射 + 账户匹配

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/entity/ImportBatch.java`
- Create: `backend/src/main/java/com/balancewise/importbill/mapper/ImportBatchMapper.java`
- Create: `backend/src/main/java/com/balancewise/importbill/service/CategoryMappingService.java`
- Create: `backend/src/main/java/com/balancewise/importbill/service/AccountMatcher.java`
- Test: `backend/src/test/java/com/balancewise/importbill/ImportMappingTest.java`

**Interfaces:**
- Consumes: `CategoryMapper`、`AccountMapper`、`Account`/`Category` 实体（P1）。
- Produces: `ImportBatch` 实体（Task 6/7 用）；`CategoryMappingService.mapCategoryId(userId, type, platformCategory, item)` 返回分类 id（无匹配 null）；`AccountMatcher.match(userId, payMethod, defaultAccountId)` 返回 `Account`（无匹配返回 defaultAccountId 对应账户，二者皆空返回 null）。Task 5 复用。

- [ ] **Step 1: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.importbill.service.AccountMatcher;
import com.balancewise.importbill.service.CategoryMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportMappingTest {

    @Autowired
    private CategoryMappingService categoryMappingService;
    @Autowired
    private AccountMatcher accountMatcher;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryMapper categoryMapper;

    private void category(String name, String kind) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setIsSystem(0);
        categoryMapper.insert(c);
    }

    @Test
    void keywordRulesMapItemTextToCategory() {
        category("餐饮", "expense");
        category("购物", "expense");
        category("交通", "expense");
        category("工资", "income");

        assertEquals(nameOf("餐饮"),
                categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "美团外卖午餐"));
        assertEquals(nameOf("购物"),
                categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "超市采购"));
        assertEquals(nameOf("交通"),
                categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "滴滴打车"));
        assertEquals(nameOf("工资"),
                categoryMappingService.mapCategoryId(1L, "income", "转账", "十月工资"));
        // 无匹配 → null（未分类）
        assertNull(categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "不知所云"));
    }

    private Long nameOf(String name) {
        return categoryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, 1L).eq(Category::getName, name)).getId();
    }

    @Test
    void accountMatchedByNameThenDefault() {
        AccountRequest r = new AccountRequest();
        r.setName("余额宝");
        r.setType("asset");
        r.setSubType("yuebao");
        r.setInitialBalance(new java.math.BigDecimal("0.00"));
        r.setRemark("");
        Account yuebao = accountService.create(1L, r);

        // 付款方式精确匹配账户名
        assertEquals(yuebao.getId(), accountMatcher.match(1L, "余额宝", null).getId());
        // 匹配不到 → 默认账户
        Account def = accountService.create(1L, reqOf("默认卡"));
        assertEquals(def.getId(), accountMatcher.match(1L, "招商银行信用卡", def.getId()).getId());
        // 匹配不到且无默认 → null
        assertNull(accountMatcher.match(1L, "火星银行", null));
    }

    private AccountRequest reqOf(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new java.math.BigDecimal("0.00"));
        r.setRemark("");
        return r;
    }
}
```

- [ ] **Step 2: 写三个实现类**

`ImportBatch.java`：

```java
package com.balancewise.importbill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("import_batch")
public class ImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** alipay / wechat */
    private String platform;
    private String fileName;
    private Integer recordCount;
    private BigDecimal totalAmount;
    /** confirmed / rolled_back */
    private String status;
    private LocalDateTime importedAt;
    private LocalDateTime rolledBackAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`ImportBatchMapper.java`：

```java
package com.balancewise.importbill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.importbill.entity.ImportBatch;

public interface ImportBatchMapper extends BaseMapper<ImportBatch> {
}
```

`CategoryMappingService.java`（内置规则：先查平台精确表，再关键词规则作用于 平台分类+商品 文本，按 type 限定 kind）：

```java
package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类自动映射（UC-4.4）：内置"平台交易分类 → 本系统分类"规则。
 * 先按平台分类精确表查，再按关键词规则匹配 平台分类+商品 文本；
 * 只在用户自己的分类中按名称解析；无匹配返回 null（未分类）。
 */
@Service
public class CategoryMappingService {

    /** 关键词规则：关键词 → 系统分类名（按顺序优先匹配） */
    private static final String[][] KEYWORD_RULES = {
            {"外卖", "餐饮"}, {"餐", "餐饮"}, {"美食", "餐饮"},
            {"超市", "购物"}, {"便利", "购物"}, {"百货", "购物"}, {"淘宝", "购物"}, {"天猫", "购物"}, {"京东", "购物"},
            {"打车", "交通"}, {"加油", "交通"}, {"地铁", "交通"}, {"公交", "交通"}, {"高铁", "交通"}, {"火车", "交通"},
            {"电影", "娱乐"}, {"会员", "娱乐"}, {"音乐", "娱乐"}, {"游戏", "娱乐"},
            {"房租", "居住"}, {"水电", "居住"}, {"物业", "居住"},
            {"工资", "工资"}, {"奖金", "工资"}, {"报销", "其他收入"},
    };

    private final CategoryMapper categoryMapper;

    public CategoryMappingService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public Long mapCategoryId(Long userId, String type, String platformCategory, String item) {
        if ("transfer".equals(type)) {
            return null; // 转账不挂分类
        }
        String kind = "expense".equals(type) ? "expense" : "income";
        String text = ((platformCategory == null ? "" : platformCategory) + " "
                + (item == null ? "" : item)).toLowerCase();
        for (String[] rule : KEYWORD_RULES) {
            if (text.contains(rule[0])) {
                Long id = resolveByName(userId, kind, rule[1]);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    private Long resolveByName(Long userId, String kind, String name) {
        List<Category> list = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .eq(Category::getName, name));
        return list.isEmpty() ? null : list.get(0).getId();
    }
}
```

`AccountMatcher.java`：

```java
package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import org.springframework.stereotype.Service;

/**
 * 入账账户匹配（UC-4.5）：按支付方式精确匹配用户账户名（如"余额宝"→余额宝账户）；
 * 匹配不到用默认账户兜底；都无则返回 null 由用户在前端指定。
 */
@Service
public class AccountMatcher {

    private final AccountMapper accountMapper;

    public AccountMatcher(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public Account match(Long userId, String payMethod, Long defaultAccountId) {
        if (payMethod != null && !payMethod.isBlank()) {
            Account hit = accountMapper.selectOne(new LambdaQueryWrapper<Account>()
                    .eq(Account::getUserId, userId)
                    .eq(Account::getName, payMethod)
                    .last("LIMIT 1"));
            if (hit != null && hit.getIsArchived() == 0) {
                return hit;
            }
        }
        if (defaultAccountId != null) {
            Account def = accountMapper.selectById(defaultAccountId);
            if (def != null && def.getUserId().equals(userId) && def.getIsArchived() == 0) {
                return def;
            }
        }
        return null;
    }
}
```

- [ ] **Step 3: 跑测试并提交**

Run: `mvn test`
Expected: 60/60

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 导入批次实体与分类映射/账户匹配服务"
```

---

### Task 5: 导入预览（不落库）

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/dto/ImportPreviewRow.java`
- Create: `backend/src/main/java/com/balancewise/importbill/vo/ImportPreviewVo.java`
- Create: `backend/src/main/java/com/balancewise/importbill/service/ImportService.java`
- Modify: `backend/src/main/resources/application.yml`（multipart 上限）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportPreviewTest.java`

**Interfaces:**
- Consumes: Task 2-4 全部（BillParser/ParsedRow/CategoryMappingService/AccountMatcher）、`TransactionService` 的查询能力（重复检测需查 transaction_record——直接用 `TransactionRecordMapper`）。
- Produces: `ImportService.preview(Long userId, String platform, byte[] content, Long defaultAccountId)` 返回 `ImportPreviewVo`；`ImportPreviewRow{lineNo,externalId,type,amount,occurredAt,counterparty,item,categoryId,fromAccountId,toAccountId,accountName,duplicate}`；`ImportPreviewVo{platform,fileName,rows,dropped}`。Task 6 confirm 复用 `ImportService`。

- [ ] **Step 1: application.yml 加上传上限**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

（嵌在既有 `spring:` 节点下。）

- [ ] **Step 2: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportPreviewRow;
import com.balancewise.importbill.vo.ImportPreviewVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportPreviewTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryMapper categoryMapper;

    private Account newAccount(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private void category(String name, String kind) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setIsSystem(0);
        categoryMapper.insert(c);
    }

    @Test
    void previewMapsCategoryAccountAndMarksNothingPersisted() throws Exception {
        newAccount("余额宝");
        newAccount("默认卡");
        Account def = newAccount("现金");
        category("餐饮", "expense");
        category("购物", "expense");

        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ImportPreviewVo vo = importService.preview(1L, "alipay", content, def.getId());

        assertEquals(4, vo.getRows().size());
        assertEquals(1, vo.getDropped().size());

        ImportPreviewRow food = vo.getRows().get(0);
        assertEquals("expense", food.getType());
        assertFalse(food.isDuplicate());
        assertNotNull(food.getCategoryId());      // 生鲜食品 → 购物（关键词"超市"? 见偏差说明）
        assertNotNull(food.getFromAccountId());   // 余额宝账户匹配

        ImportPreviewRow transfer = vo.getRows().get(2);
        assertEquals("transfer", transfer.getType());
        assertNull(transfer.getCategoryId());     // 转账不挂分类
        assertNotNull(transfer.getFromAccountId());
    }

    @Test
    void duplicateRowsMarkedAgainstDbAndInBatch() throws Exception {
        Account def = newAccount("现金");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        // 第一次预览：全部不重复
        ImportPreviewVo first = importService.preview(1L, "alipay", content, def.getId());
        assertTrue(first.getRows().stream().noneMatch(ImportPreviewRow::isDuplicate));
        // 模拟已入账：直接插一条同 external_id 未删除流水
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
        // 第二次预览：该行标记重复
        ImportPreviewVo second = importService.preview(1L, "alipay", content, def.getId());
        assertTrue(second.getRows().get(0).isDuplicate());
        assertFalse(second.getRows().get(1).isDuplicate());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.balancewise.record.mapper.TransactionRecordMapper transactionRecordMapper;
}
```

注意：上面第一个测试中 `生鲜食品 → 购物` 的注释——按 Task 4 关键词规则，"生鲜食品"文本含"超"吗？不含。含"食品"吗？规则无"食品"。所以**该断言会红**；实现时把注释改为正确预期：生鲜食品不含任何关键词 → categoryId 为 null（未分类）。**修正断言为 `assertNull(food.getCategoryId());` 及注释「无关键词命中 → 未分类」**，同时给第二个样本行"美团外卖午餐"（含"外卖"→餐饮）加断言：

```java
        ImportPreviewRow waimai = vo.getRows().get(1);
        assertNotNull(waimai.getCategoryId()); // 外卖午餐 → 餐饮
```

（这是简报预期修正，实现者按此执行。）

- [ ] **Step 3: 实现三个类**

`ImportPreviewRow.java`：

```java
package com.balancewise.importbill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ImportPreviewRow {
    private int lineNo;
    private String externalId;
    private String type;
    private BigDecimal amount;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    private Long categoryId;
    private Long fromAccountId;
    private Long toAccountId;
    private String accountName;
    /** true = 与库中未删除记录或批内记录重复，默认不勾选 */
    private boolean duplicate;
}
```

`ImportPreviewVo.java`：

```java
package com.balancewise.importbill.vo;

import com.balancewise.importbill.dto.ImportPreviewRow;
import lombok.Data;

import java.util.List;

@Data
public class ImportPreviewVo {
    private String platform;
    private String fileName;
    private List<ImportPreviewRow> rows;
    private List<String> dropped;
}
```

`ImportService.java`（本任务只实现 preview 与 parser 注册；confirm 在 Task 6 追加）：

```java
package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.BillParser;
import com.balancewise.importbill.parser.ParsedRow;
import com.balancewise.importbill.vo.ImportPreviewVo;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 账单导入编排（UC-4.1~4.7 预览侧）。
 * 预览不落库：解析 → 清洗 → 类型识别 → 分类映射 → 账户匹配 → 重复检测（库中 is_deleted=0 同源 + 批内），返回预览行。
 */
@Service
public class ImportService {

    private final Map<String, BillParser> parsers;
    private final CategoryMappingService categoryMappingService;
    private final AccountMatcher accountMatcher;
    private final TransactionRecordMapper transactionRecordMapper;

    public ImportService(List<BillParser> parserList,
                         CategoryMappingService categoryMappingService,
                         AccountMatcher accountMatcher,
                         TransactionRecordMapper transactionRecordMapper) {
        this.parsers = parserList.stream().collect(Collectors.toMap(BillParser::platform, Function.identity()));
        this.categoryMappingService = categoryMappingService;
        this.accountMatcher = accountMatcher;
        this.transactionRecordMapper = transactionRecordMapper;
    }

    public ImportPreviewVo preview(Long userId, String platform, byte[] content, Long defaultAccountId) {
        BillParser parser = parsers.get(platform);
        if (parser == null) {
            throw new BizException("不支持的平台：" + platform);
        }
        BillParseResult parsed = parser.parse(content);
        List<String> externalIds = parsed.getRows().stream()
                .map(ParsedRow::getExternalId).filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        Set<String> existing = existingExternalIds(userId, platform, externalIds);
        Set<String> seenInBatch = new HashSet<>();

        List<ImportPreviewRow> rows = parsed.getRows().stream().map(r -> {
            ImportPreviewRow row = new ImportPreviewRow();
            row.setLineNo(r.getLineNo());
            row.setExternalId(r.getExternalId());
            row.setType(r.getType());
            row.setAmount(r.getAmount());
            row.setOccurredAt(r.getOccurredAt());
            row.setCounterparty(r.getCounterparty());
            row.setItem(r.getItem());
            row.setCategoryId(categoryMappingService.mapCategoryId(
                    userId, r.getType(), r.getPlatformCategory(), r.getItem()));
            Account acc = accountMatcher.match(userId, r.getPayMethod(), defaultAccountId);
            if (acc != null) {
                row.setAccountName(acc.getName());
                if ("income".equals(r.getType())) {
                    row.setToAccountId(acc.getId());
                } else {
                    // expense / transfer：先填 from（转账的 to 由用户在预览中补选）
                    row.setFromAccountId(acc.getId());
                }
            }
            boolean dup = existing.contains(r.getExternalId()) || !seenInBatch.add(r.getExternalId());
            row.setDuplicate(dup);
            return row;
        }).collect(Collectors.toList());

        ImportPreviewVo vo = new ImportPreviewVo();
        vo.setPlatform(platform);
        vo.setRows(rows);
        vo.setDropped(parsed.getDropped());
        return vo;
    }

    /** 库中已存在的同源未删除 external_id（§5.4 规则 4：应用层重复检测） */
    private Set<String> existingExternalIds(Long userId, String platform, List<String> externalIds) {
        if (externalIds.isEmpty()) {
            return Set.of();
        }
        return transactionRecordMapper.selectList(new LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getUserId, userId)
                        .eq(TransactionRecord::getSource, platform)
                        .eq(TransactionRecord::getIsDeleted, 0)
                        .in(TransactionRecord::getExternalId, externalIds))
                .stream().map(TransactionRecord::getExternalId).collect(Collectors.toSet());
    }
}
```

（`fileName` 在 preview 阶段由 Task 8 controller 填充；本任务 VO 保留字段。）

- [ ] **Step 4: 跑测试并提交**

Run: `mvn test`
Expected: 62/62（按上述断言修正后）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 导入预览（解析/映射/匹配/重复检测，不落库）"
```

---

### Task 6: 批次确认入账（单事务）

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/dto/ImportRowRequest.java`
- Create: `backend/src/main/java/com/balancewise/importbill/dto/ImportConfirmRequest.java`
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`（抽出 createInternal + importRecord 公共方法）
- Modify: `backend/src/main/java/com/balancewise/importbill/service/ImportService.java`（追加 confirm）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportConfirmTest.java`

**Interfaces:**
- Consumes: Task 2-5 全部。
- Produces: `TransactionService.importRecord(Long userId, String source, String externalId, Long importBatchId, TransactionRequest req)`（公共，Task 7 撤销冲销的逆向也走 `applyBalance`）；`ImportService.confirm(Long userId, ImportConfirmRequest req)` 返回 `ImportBatch`。

- [ ] **Step 1: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.dto.ImportRowRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportPreviewVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportConfirmTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private AccountService accountService;

    private Account newAccount(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private ImportConfirmRequest confirmOf(ImportPreviewVo preview, Account defFrom, Account defTo) {
        List<ImportRowRequest> rows = preview.getRows().stream()
                .filter(r -> !r.isDuplicate())
                .map(r -> {
                    ImportRowRequest row = new ImportRowRequest();
                    row.setType(r.getType());
                    row.setAmount(r.getAmount());
                    row.setOccurredAt(r.getOccurredAt());
                    row.setCounterparty(r.getCounterparty());
                    row.setItem(r.getItem());
                    row.setExternalId(r.getExternalId());
                    row.setCategoryId(r.getCategoryId());
                    // 预览只填了 from（转账缺 to），这里分别补兜底账户（from/to 不同，避免转账同账户校验失败）
                    row.setFromAccountId(r.getFromAccountId() != null ? r.getFromAccountId() : defFrom.getId());
                    row.setToAccountId(r.getToAccountId() != null ? r.getToAccountId() : defTo.getId());
                    return row;
                }).collect(Collectors.toList());
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform(preview.getPlatform());
        req.setFileName("alipay-sample.csv");
        req.setRows(rows);
        return req;
    }

    @Test
    void confirmPersistsBatchRecordsAndBalances() throws Exception {
        Account yuebao = newAccount("余额宝");
        Account defFrom = newAccount("现金");
        Account defTo = newAccount("储蓄卡");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ImportPreviewVo preview = importService.preview(1L, "alipay", content, defFrom.getId());

        ImportBatch batch = importService.confirm(1L, confirmOf(preview, defFrom, defTo));
        assertNotNull(batch.getId());
        assertEquals("confirmed", batch.getStatus());
        assertEquals(4, batch.getRecordCount());
        // 35.50 + 25.00 + 1000.00 + 35.50 = 1096.00
        assertEquals(0, new BigDecimal("1096.00").compareTo(batch.getTotalAmount()));

        // 行0 支出 35.50 from 余额宝；行3 收入 35.50 to 余额宝 → 余额宝净额 0
        assertEquals(0, BigDecimal.ZERO.compareTo(
                accountService.getById(yuebao.getId()).getBalance()));
        // 行1 支出 25.00 from 现金；行2 转账 1000.00 from 现金 → 现金 -1025.00
        assertEquals(0, new BigDecimal("-1025.00")
                .compareTo(accountService.getById(defFrom.getId()).getBalance()));
        // 行2 转账 1000.00 to 储蓄卡 → +1000.00
        assertEquals(0, new BigDecimal("1000.00")
                .compareTo(accountService.getById(defTo.getId()).getBalance()));
    }

    @Test
    void duplicateConfirmRejected() throws Exception {
        Account defFrom = newAccount("现金");
        Account defTo = newAccount("储蓄卡");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ImportConfirmRequest req = confirmOf(importService.preview(1L, "alipay", content, defFrom.getId()), defFrom, defTo);
        importService.confirm(1L, req);
        // 同一批 external_id 再次入账 → 拒
        BizException e = assertThrows(BizException.class, () -> importService.confirm(1L, req));
        assertTrue(e.getMessage().contains("已入账"));
    }

    @Test
    void emptyRowsRejected() {
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("x.csv");
        req.setRows(List.of());
        BizException e = assertThrows(BizException.class, () -> importService.confirm(1L, req));
        assertTrue(e.getMessage().contains("至少一条"));
    }
}
```

- [ ] **Step 2: TransactionService 抽出共用写入路径**

把现有 `create` 改为委托内部方法（行为不变），并新增 `importRecord`：

```java
    @Transactional
    public TransactionRecord create(Long userId, TransactionRequest req) {
        return createInternal(userId, req, "manual", null, null);
    }

    /**
     * 导入入账（P3）：与手工记账共用同一校验与余额联动路径，
     * 仅 source/externalId/importBatchId 不同。绝不复制余额逻辑。
     */
    @Transactional
    public TransactionRecord importRecord(Long userId, String source, String externalId,
                                          Long importBatchId, TransactionRequest req) {
        return createInternal(userId, req, source, externalId, importBatchId);
    }

    private TransactionRecord createInternal(Long userId, TransactionRequest req,
                                             String source, String externalId, Long importBatchId) {
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
        r.setSource(source);
        r.setExternalId(externalId);
        r.setImportBatchId(importBatchId);
        r.setIsDeleted(0);
        save(r);

        applyBalance(accounts[0], accounts[1], amount);
        return r;
    }
```

（原 `create` 方法体删除，由上述替代。既有测试必须全绿不变。）

- [ ] **Step 3: 两个 DTO + ImportService.confirm**

`ImportRowRequest.java`：

```java
package com.balancewise.importbill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ImportRowRequest {
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
    private String externalId;
}
```

`ImportConfirmRequest.java`：

```java
package com.balancewise.importbill.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class ImportConfirmRequest {
    @NotBlank(message = "平台不能为空")
    @Pattern(regexp = "alipay|wechat", message = "平台必须是 alipay/wechat")
    private String platform;
    private String fileName;
    @Valid
    @NotEmpty(message = "请至少勾选一条流水")
    private List<ImportRowRequest> rows;
}
```

`ImportService` 追加（需要注入 `ImportBatchMapper` 与 `TransactionService`，构造器相应扩展）：

```java
    /**
     * 批次入账（UC-4.8 / 规格 7.4 步骤 7）：单事务——
     * 重复校验 → 插 import_batch(confirmed) → 逐条经 TransactionService.importRecord 入账（余额联动同生共死）。
     */
    @Transactional
    public ImportBatch confirm(Long userId, ImportConfirmRequest req) {
        List<String> externalIds = req.getRows().stream()
                .map(ImportRowRequest::getExternalId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        long dupCount = existingExternalIds(userId, req.getPlatform(), externalIds).size();
        if (dupCount > 0) {
            throw new BizException("包含 " + dupCount + " 条已入账记录，请重新预览后再确认");
        }

        ImportBatch batch = new ImportBatch();
        batch.setUserId(userId);
        batch.setPlatform(req.getPlatform());
        batch.setFileName(req.getFileName() != null ? req.getFileName() : "");
        batch.setRecordCount(req.getRows().size());
        batch.setTotalAmount(req.getRows().stream()
                .map(ImportRowRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP));
        batch.setStatus("confirmed");
        batch.setImportedAt(java.time.LocalDateTime.now());
        importBatchMapper.insert(batch);

        for (ImportRowRequest row : req.getRows()) {
            com.balancewise.record.dto.TransactionRequest txn = new com.balancewise.record.dto.TransactionRequest();
            txn.setType(row.getType());
            txn.setFromAccountId(row.getFromAccountId());
            txn.setToAccountId(row.getToAccountId());
            txn.setAmount(row.getAmount());
            txn.setCategoryId(row.getCategoryId());
            txn.setOccurredAt(row.getOccurredAt());
            txn.setCounterparty(row.getCounterparty());
            txn.setItem(row.getItem());
            transactionService.importRecord(userId, req.getPlatform(), row.getExternalId(),
                    batch.getId(), txn);
        }
        return batch;
    }
```

（文件头追加 import：`com.balancewise.importbill.entity.ImportBatch`、`com.balancewise.importbill.mapper.ImportBatchMapper`、`com.balancewise.record.service.TransactionService`、`java.math.BigDecimal`。构造器注入扩展两个依赖。）

- [ ] **Step 4: 跑测试并提交**

Run: `mvn test`
Expected: 65/65（62 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 批次确认入账（单事务，复用余额联动单一抽象）"
```

---

### Task 7: 批次列表 / 明细 / 幂等撤销

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/vo/ImportBatchVo.java`
- Create: `backend/src/main/java/com/balancewise/importbill/vo/ImportBatchDetailVo.java`
- Modify: `backend/src/main/java/com/balancewise/record/service/TransactionService.java`（enrich 改 public + 新增 reverse）
- Modify: `backend/src/main/java/com/balancewise/importbill/service/ImportService.java`（追加 list/detail/rollback）
- Test: `backend/src/test/java/com/balancewise/importbill/ImportBatchTest.java`

**Interfaces:**
- Consumes: Task 6。
- Produces: `ImportService.list(Long userId)` 返回 `List<ImportBatchVo>`；`detail(Long userId, Long batchId)` 返回 `ImportBatchDetailVo`（含 `List<TransactionVo> records`，含已软删流水）；`rollback(Long userId, Long batchId)`；`TransactionService.reverse(TransactionRecord r)` 公共冲销（撤销用）。

- [ ] **Step 1: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.dto.ImportRowRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportBatchDetailVo;
import com.balancewise.importbill.vo.ImportBatchVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportBatchTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private AccountService accountService;

    private Long confirmedBatchWithOneExpense() {
        AccountRequest r = new AccountRequest();
        r.setName("现金");
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        Account def = accountService.create(1L, r);

        ImportRowRequest row = new ImportRowRequest();
        row.setType("expense");
        row.setFromAccountId(def.getId());
        row.setAmount(new BigDecimal("100.00"));
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setExternalId("EXT-001");
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("t.csv");
        req.setRows(List.of(row));
        return importService.confirm(1L, req).getId();
    }

    @Test
    void rollbackRestoresBalancesAndSoftDeletes() {
        Account cash = accountService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, 1L).eq(Account::getName, "现金"));
        Long batchId = confirmedBatchWithOneExpense();
        // 注意：必须重新 getById——confirm 前查到的实体是余额变动前的快照
        assertEquals(0, new BigDecimal("-100.00").compareTo(
                accountService.getById(cash.getId()).getBalance()));

        ImportBatch after = importService.rollback(1L, batchId);
        assertEquals("rolled_back", after.getStatus());
        assertNotNull(after.getRolledBackAt());
        // 余额还原
        assertEquals(0, BigDecimal.ZERO.compareTo(
                accountService.getById(cash.getId()).getBalance()));
        // 明细仍可见（软删保留，可追溯）
        ImportBatchDetailVo detail = importService.detail(1L, batchId);
        assertEquals(1, detail.getRecords().size());
        assertEquals("EXT-001", detail.getRecords().get(0).getExternalId());
    }

    @Test
    void doubleRollbackRejected() {
        Long batchId = confirmedBatchWithOneExpense();
        importService.rollback(1L, batchId);
        BizException e = assertThrows(BizException.class, () -> importService.rollback(1L, batchId));
        assertTrue(e.getMessage().contains("已撤销"));
    }

    @Test
    void listShowsBatchesAndOtherUserRejected() {
        Long batchId = confirmedBatchWithOneExpense();
        List<ImportBatchVo> list = importService.list(1L);
        assertEquals(1, list.size());
        assertEquals(batchId, list.get(0).getId());
        assertEquals("confirmed", list.get(0).getStatus());
        assertEquals("t.csv", list.get(0).getFileName());

        BizException e = assertThrows(BizException.class, () -> importService.detail(2L, batchId));
        assertEquals("批次不存在", e.getMessage());
        BizException e2 = assertThrows(BizException.class, () -> importService.rollback(2L, batchId));
        assertEquals("批次不存在", e2.getMessage());
    }
}
```

- [ ] **Step 2: 两个 VO + TransactionService 两个改动**

`ImportBatchVo.java`：

```java
package com.balancewise.importbill.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ImportBatchVo {
    private Long id;
    private String platform;
    private String fileName;
    private Integer recordCount;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime importedAt;
    private LocalDateTime rolledBackAt;
}
```

`ImportBatchDetailVo.java`：

```java
package com.balancewise.importbill.vo;

import com.balancewise.record.vo.TransactionVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ImportBatchDetailVo extends ImportBatchVo {
    private List<TransactionVo> records;
}
```

`TransactionService`：把 `enrich` 从 `protected`/`private` 改为 `public`（Task 7/8 复用），并追加公共冲销：

```java
    /** 反向冲销一条流水（批次撤销用）：from 加回、to 减回。调用方负责软删标记与事务。 */
    public void reverse(TransactionRecord r) {
        applyBalance(r.getFromAccountId(), r.getToAccountId(), r.getAmount().negate());
    }
```

`ImportService` 追加（并注入 `TransactionService`——Task 6 已注入）：

```java
    public List<ImportBatchVo> list(Long userId) {
        return importBatchMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ImportBatch>()
                        .eq(ImportBatch::getUserId, userId)
                        .orderByDesc(ImportBatch::getImportedAt))
                .stream().map(this::toVo).collect(Collectors.toList());
    }

    public ImportBatchDetailVo detail(Long userId, Long batchId) {
        ImportBatch batch = ownedBatch(userId, batchId);
        ImportBatchDetailVo vo = new ImportBatchDetailVo();
        vo.setId(batch.getId());
        vo.setPlatform(batch.getPlatform());
        vo.setFileName(batch.getFileName());
        vo.setRecordCount(batch.getRecordCount());
        vo.setTotalAmount(batch.getTotalAmount());
        vo.setStatus(batch.getStatus());
        vo.setImportedAt(batch.getImportedAt());
        vo.setRolledBackAt(batch.getRolledBackAt());
        // 明细含已软删流水（可追溯）
        List<TransactionRecord> records = transactionRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getImportBatchId, batchId)
                        .orderByDesc(TransactionRecord::getOccurredAt));
        vo.setRecords(transactionService.enrich(records));
        return vo;
    }

    /**
     * 批次撤销（UC-4.11 / 规格 7.5）：单事务——
     * 状态机校验（confirmed，幂等：重复撤销拒绝）→ 逐条按当前值反向冲销（编辑过以当前值为准）→ 软删 → 批次置 rolled_back。
     * 撤销后该文件可重新导入（重复检测只比对未删除记录）。
     */
    @Transactional
    public ImportBatch rollback(Long userId, Long batchId) {
        ImportBatch batch = ownedBatch(userId, batchId);
        if (!"confirmed".equals(batch.getStatus())) {
            throw new BizException("批次已撤销，请勿重复操作");
        }
        List<TransactionRecord> records = transactionRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getImportBatchId, batchId)
                        .eq(TransactionRecord::getIsDeleted, 0));
        for (TransactionRecord r : records) {
            transactionService.reverse(r);
            r.setIsDeleted(1);
            transactionRecordMapper.updateById(r);
        }
        batch.setStatus("rolled_back");
        batch.setRolledBackAt(java.time.LocalDateTime.now());
        importBatchMapper.updateById(batch);
        return batch;
    }

    private ImportBatch ownedBatch(Long userId, Long batchId) {
        ImportBatch batch = importBatchMapper.selectById(batchId);
        if (batch == null || !batch.getUserId().equals(userId)) {
            throw new BizException("批次不存在");
        }
        return batch;
    }

    private ImportBatchVo toVo(ImportBatch b) {
        ImportBatchVo vo = new ImportBatchVo();
        vo.setId(b.getId());
        vo.setPlatform(b.getPlatform());
        vo.setFileName(b.getFileName());
        vo.setRecordCount(b.getRecordCount());
        vo.setTotalAmount(b.getTotalAmount());
        vo.setStatus(b.getStatus());
        vo.setImportedAt(b.getImportedAt());
        vo.setRolledBackAt(b.getRolledBackAt());
        return vo;
    }
```

（追加 import：`ImportBatchVo`、`ImportBatchDetailVo`、`TransactionRecord`、`TransactionService`、`java.util.stream.Collectors` 视情况补。）

- [ ] **Step 3: 跑测试并提交**

Run: `mvn test`
Expected: 68/68（65 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 批次列表/明细/幂等撤销（按当前值冲销）"
```

---

### Task 8: ImportController + MockMvc 集成测试

**Files:**
- Create: `backend/src/main/java/com/balancewise/importbill/controller/ImportController.java`
- Test: `backend/src/test/java/com/balancewise/importbill/ImportApiTest.java`

**Interfaces:**
- Consumes: Task 5-7 Service 方法；`AuthApiTest` 的 MockMvc 模式。
- Produces: REST 端点（规格 §6 导入节）：`POST /api/imports/preview`（multipart）、`POST /api/imports/confirm`、`GET /api/imports`、`GET /api/imports/{id}`、`POST /api/imports/{id}/rollback`。

- [ ] **Step 1: 写失败测试**

```java
package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImportApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long defaultAccount() {
        AccountRequest r = new AccountRequest();
        r.setName("现金");
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r).getId();
    }

    @Test
    void previewConfirmListDetailRollbackFlow() throws Exception {
        String token = jwtUtil.generate(1L);
        Long defId = defaultAccount();
        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        MockMultipartFile file = new MockMultipartFile(
                "file", "alipay-sample.csv", "text/csv", csv);

        // 预览
        MvcResult preview = mockMvc.perform(multipart("/api/imports/preview")
                        .file(file)
                        .param("platform", "alipay")
                        .param("defaultAccountId", String.valueOf(defId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(
                        preview.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("rows");
        assertEquals(4, rows.size());

        // 确认（勾选 2 条，补账户）
        StringBuilder body = new StringBuilder("{\"platform\":\"alipay\",\"fileName\":\"alipay-sample.csv\",\"rows\":[");
        for (int i = 0; i < 2; i++) {
            JsonNode r = rows.get(i);
            if (i > 0) body.append(',');
            body.append("{\"type\":\"").append(r.path("type").asText())
                .append("\",\"fromAccountId\":").append(defId)
                .append(",\"toAccountId\":").append(defId)
                .append(",\"amount\":").append(r.path("amount").asText())
                .append(",\"occurredAt\":\"").append(r.path("occurredAt").asText()).append("\"")
                .append(",\"counterparty\":\"").append(r.path("counterparty").asText()).append("\"")
                .append(",\"externalId\":\"").append(r.path("externalId").asText()).append("\"}");
        }
        body.append("]}");
        MvcResult confirmed = mockMvc.perform(post("/api/imports/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        long batchId = objectMapper.readTree(confirmed.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 列表 + 明细
        mockMvc.perform(get("/api/imports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult detail = mockMvc.perform(get("/api/imports/" + batchId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(2, objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("records").size());

        // 撤销
        mockMvc.perform(post("/api/imports/" + batchId + "/rollback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 重复撤销 → 400
        MvcResult again = mockMvc.perform(post("/api/imports/" + batchId + "/rollback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(again.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void previewWithoutAuthReturns401() throws Exception {
        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        mockMvc.perform(multipart("/api/imports/preview")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", csv))
                        .param("platform", "alipay"))
                .andExpect(status().is(401));
    }

    @Test
    void wrongPlatformHeaderReturns400() throws Exception {
        String token = jwtUtil.generate(1L);
        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        MvcResult res = mockMvc.perform(multipart("/api/imports/preview")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", csv))
                        .param("platform", "alipay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        // 正常解析应成功（code=0），若文件与平台不匹配则 code=400
        assertEquals(0, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }
}
```

- [ ] **Step 2: 实现 ImportController**

```java
package com.balancewise.importbill.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportBatchDetailVo;
import com.balancewise.importbill.vo.ImportBatchVo;
import com.balancewise.importbill.vo.ImportPreviewVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** 上传解析预览（不落库）。multipart：platform + file + defaultAccountId(可选) */
    @PostMapping("/preview")
    public Result<ImportPreviewVo> preview(@CurrentUser Long userId,
                                           @RequestParam String platform,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) Long defaultAccountId)
            throws IOException {
        ImportPreviewVo vo = importService.preview(userId, platform, file.getBytes(), defaultAccountId);
        vo.setFileName(file.getOriginalFilename());
        return Result.ok(vo);
    }

    @PostMapping("/confirm")
    public Result<ImportBatch> confirm(@CurrentUser Long userId,
                                       @Valid @RequestBody ImportConfirmRequest req) {
        return Result.ok(importService.confirm(userId, req));
    }

    @GetMapping
    public Result<List<ImportBatchVo>> list(@CurrentUser Long userId) {
        return Result.ok(importService.list(userId));
    }

    @GetMapping("/{id}")
    public Result<ImportBatchDetailVo> detail(@CurrentUser Long userId, @PathVariable Long id) {
        return Result.ok(importService.detail(userId, id));
    }

    @PostMapping("/{id}/rollback")
    public Result<ImportBatch> rollback(@CurrentUser Long userId, @PathVariable Long id) {
        return Result.ok(importService.rollback(userId, id));
    }
}
```

- [ ] **Step 3: 跑测试并提交**

Run: `mvn test`
Expected: 71/71（68 既有 + 3 新增）

```bash
git add backend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 导入 REST 接口与 MockMvc 集成测试"
```

---

### Task 9: 前端导入页

**Files:**
- Create: `frontend/src/views/ImportView.vue`
- Modify: `frontend/src/router/index.js:14`（import 路由指向 ImportView）

**Interfaces:**
- Consumes: Task 8 五个端点；`GET /api/accounts`（P1）；`GET /api/categories`（P1）。
- Produces: 路由 `/import` 指向 ImportView。

- [ ] **Step 1: 改路由**

```js
      { path: 'import', component: () => import('../views/ImportView.vue'), meta: { title: '账单导入' } },
```

- [ ] **Step 2: 写 ImportView.vue**

```vue
<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="导入账单" name="upload">
        <el-card style="max-width: 720px; margin-bottom: 16px">
          <el-form label-width="90px" inline @submit.prevent>
            <el-form-item label="平台">
              <el-radio-group v-model="form.platform">
                <el-radio-button value="alipay">支付宝</el-radio-button>
                <el-radio-button value="wechat">微信</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="默认账户">
              <el-select v-model="form.defaultAccountId" filterable clearable placeholder="匹配不到时使用的账户" style="width: 180px">
                <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="文件">
              <el-upload ref="uploadRef" :auto-upload="false" :limit="1" accept=".csv"
                         :on-exceed="onExceed" :on-change="onFileChange" :on-remove="onFileRemove">
                <el-button>选择 CSV 文件</el-button>
                <template #tip><span class="muted" style="margin-left: 10px">≤ 10 MB，支付宝/微信官方导出格式</span></template>
              </el-upload>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="parsing" @click="parse">解析预览</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-if="preview.rows.length || preview.dropped.length">
          <template #header>
            <div class="card-head">
              <span>预览（{{ preview.rows.length }} 条，丢弃 {{ preview.dropped.length }} 条）</span>
              <el-button type="primary" :disabled="!checkedCount" :loading="confirming"
                         @click="confirm">确认入账（{{ checkedCount }} 条）</el-button>
            </div>
          </template>
          <div v-if="preview.dropped.length" class="muted" style="margin-bottom: 8px">
            丢弃：{{ preview.dropped.join('；') }}
          </div>
          <el-table :data="preview.rows" size="small" max-height="480">
            <el-table-column label="时间" prop="occurredAt" width="160" />
            <el-table-column label="类型" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'expense' ? 'danger' : row.type === 'income' ? 'success' : 'info'">
                  {{ { expense: '支出', income: '收入', transfer: '转账' }[row.type] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="对方" prop="counterparty" width="120" show-overflow-tooltip />
            <el-table-column label="商品" prop="item" width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.item || '—' }}</template>
            </el-table-column>
            <el-table-column label="分类" width="160">
              <template #default="{ row }">
                <el-cascader v-if="row.type !== 'transfer'" v-model="row.categoryId"
                             :options="optionsOf(row.type)" :props="cascaderProps"
                             filterable clearable placeholder="未分类" size="small" style="width: 100%" />
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="账户" min-width="220">
              <template #default="{ row }">
                <div class="acc-cell">
                  <el-select v-if="row.type !== 'income'" v-model="row.fromAccountId" filterable size="small"
                             placeholder="转出/支出账户" style="width: 100px">
                    <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                  <el-select v-if="row.type !== 'expense'" v-model="row.toAccountId" filterable size="small"
                             :placeholder="row.type === 'transfer' ? '转入账户' : '收入账户'" style="width: 100px">
                    <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="重复" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.duplicate" size="small" type="warning">重复</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="入账" width="55">
              <template #default="{ row }">
                <el-checkbox v-model="row.checked" :disabled="row.duplicate" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="批次记录" name="batches">
        <el-card>
          <el-table :data="batches" size="small">
            <el-table-column label="入账时间" prop="importedAt" width="165" />
            <el-table-column label="平台" width="80">
              <template #default="{ row }">{{ row.platform === 'alipay' ? '支付宝' : '微信' }}</template>
            </el-table-column>
            <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
            <el-table-column prop="recordCount" label="笔数" width="70" align="right" />
            <el-table-column label="合计金额" width="110" align="right">
              <template #default="{ row }">{{ money(row.totalAmount) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'confirmed' ? 'success' : 'info'">
                  {{ row.status === 'confirmed' ? '已入账' : '已撤销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button size="small" text @click="openDetail(row)">明细</el-button>
                <el-button v-if="row.status === 'confirmed'" size="small" text type="danger" @click="rollback(row)">撤销</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无导入批次" :image-size="60" /></template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detail.visible" title="批次明细" width="760px">
      <el-table :data="detail.records" size="small" max-height="420">
        <el-table-column prop="occurredAt" label="时间" width="160" />
        <el-table-column label="类型" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'expense' ? 'danger' : row.type === 'income' ? 'success' : 'info'">
              {{ { expense: '支出', income: '收入', transfer: '转账' }[row.type] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="100">
          <template #default="{ row }">{{ row.categoryName || '—' }}</template>
        </el-table-column>
        <el-table-column label="账户" min-width="140">
          <template #default="{ row }">
            {{ row.type === 'transfer' ? `${row.fromAccountName || ''} → ${row.toAccountName || ''}`
              : row.type === 'expense' ? (row.fromAccountName || '—') : (row.toAccountName || '—') }}
          </template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="counterparty" label="对方" width="120" show-overflow-tooltip />
        <el-table-column prop="item" label="商品" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const tab = ref('upload')
const accounts = ref([])
const expenseTree = ref([])
const incomeTree = ref([])
const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: false }
const uploadRef = ref()
const pickedFile = ref(null)

const form = reactive({ platform: 'alipay', defaultAccountId: null })
const parsing = ref(false)
const confirming = ref(false)
const preview = reactive({ fileName: '', rows: [], dropped: [] })
const batches = ref([])

const optionsOf = type => (type === 'income' ? incomeTree.value : expenseTree.value)
const checkedCount = computed(() => preview.rows.filter(r => r.checked && !r.duplicate).length)
const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function onFileChange(file) {
  pickedFile.value = file.raw
}
function onFileRemove() {
  pickedFile.value = null
}
function onExceed(files) {
  // 只保留最新选择的文件
  uploadRef.value.clearFiles()
  uploadRef.value.handleStart(files[0])
  pickedFile.value = files[0]
}

onMounted(async () => {
  const [acc, exp, inc] = await Promise.all([
    request.get('/accounts'),
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  accounts.value = acc.data.accounts.filter(a => !a.isArchived)
  expenseTree.value = exp.data
  incomeTree.value = inc.data
  await loadBatches()
})

async function parse() {
  if (!pickedFile.value) return ElMessage.warning('请先选择 CSV 文件')
  parsing.value = true
  try {
    const fd = new FormData()
    fd.append('platform', form.platform)
    fd.append('file', pickedFile.value)
    if (form.defaultAccountId) fd.append('defaultAccountId', form.defaultAccountId)
    const res = await request.post('/imports/preview', fd)
    preview.fileName = res.data.fileName
    preview.dropped = res.data.dropped
    preview.rows = res.data.rows.map(r => ({ ...r, checked: !r.duplicate }))
    if (!res.data.rows.length) ElMessage.warning('没有可导入的流水')
  } finally {
    parsing.value = false
  }
}

async function confirm() {
  const missing = preview.rows.find(r => r.checked && !r.duplicate
    && (r.type !== 'income' && !r.fromAccountId || r.type !== 'expense' && !r.toAccountId))
  if (missing) return ElMessage.warning(`第 ${missing.lineNo} 行还未选择账户`)
  confirming.value = true
  try {
    await request.post('/imports/confirm', {
      platform: form.platform,
      fileName: preview.fileName,
      rows: preview.rows.filter(r => r.checked && !r.duplicate).map(r => ({
        type: r.type, amount: r.amount, occurredAt: r.occurredAt,
        counterparty: r.counterparty, item: r.item, externalId: r.externalId,
        categoryId: r.type === 'transfer' ? null : r.categoryId,
        fromAccountId: r.type === 'income' ? null : r.fromAccountId,
        toAccountId: r.type === 'expense' ? null : r.toAccountId,
      })),
    })
    ElMessage.success('批次已入账')
    preview.rows = []
    preview.dropped = []
    uploadRef.value.clearFiles()
    pickedFile.value = null
    tab.value = 'batches'
    await loadBatches()
  } finally {
    confirming.value = false
  }
}

async function loadBatches() {
  const res = await request.get('/imports')
  batches.value = res.data
}

const detail = reactive({ visible: false, records: [] })

async function openDetail(row) {
  const res = await request.get(`/imports/${row.id}`)
  detail.records = res.data.records
  detail.visible = true
}

async function rollback(row) {
  try {
    await ElMessageBox.confirm(
      `确定撤销批次「${row.fileName}」？该批 ${row.recordCount} 条流水将冲销余额并软删除，文件可重新导入。`,
      '撤销确认', { type: 'warning' })
  } catch { return }
  await request.post(`/imports/${row.id}/rollback`)
  ElMessage.success('批次已撤销')
  await loadBatches()
}
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.acc-cell { display: flex; gap: 6px; }
.muted { color: #8a90a3; font-size: 12px; }
</style>
```

- [ ] **Step 3: 构建并提交**

Run: `cd frontend && npm run build`
Expected: 构建成功

```bash
git add frontend
git -c user.name="李东升" -c user.email="lids@local" commit -m "feat(p3): 前端导入页（上传/预览/勾选确认/批次明细/撤销）"
```

---

### Task 10: P3 端到端验证与阶段收尾

**Files:**
- Modify: `README.md`（功能清单追加账单导入）

- [ ] **Step 1: 后端全量回归**

Run: `cd backend && mvn test`
Expected: 71/71（数字以实际类计数为准记录）

- [ ] **Step 2: e2e（curl + 真实 MySQL，用户 tester01/123456）**

后端后台启动（powershell Start-Process，日志 `logs/backend-p3-e2e.log`）。MySQL 在后台（`.tools\mysql\bin\mysql.exe -u root -proot balancewise`）。中文请求体写 UTF-8 文件；样例 CSV 直接用仓库里的 `backend/src/test/resources/fixtures/alipay-sample.csv`（curl `-F "file=@...;type=text/csv"`）。

1. 登录拿 token；确认 tester01 有「余额宝」账户（没有则建：POST /api/accounts，sub_type=yuebao，余额 0）与「餐饮/购物」预置分类（39 个预置自带）。
2. `POST /api/imports/preview`（platform=alipay，file=样例，defaultAccountId=现金账户 id）→ 4 行、1 丢弃、第 1 行 accountName=余额宝、**无重复标记**。
3. 再次 preview **同文件** → 仍未重复（因为第 2 步未入账）。
4. confirm 勾选 4 行（**转账行的 to 账户必须选与 from 不同的账户**，如 from=现金、to=余额宝，否则触发"转出与转入账户不能相同"）→ code=0，batch id 记录。
5. `GET /api/accounts` 断言：余额宝余额 = -1000.00（支出 35.50 + 余额宝转入 1000.00 从"余额"付出——若"余额"账户不存在匹配失败，转账行 from 用默认账户，则断言按实际账户分配记录；实现者须记录每行账户分配与余额变化表）。
6. `GET /api/transactions?type=expense` 可见 source=alipay 流水，含 externalId。
7. 再次 preview 同文件 → 4 行全部标记重复。
8. 同批 confirm 重复提交 → code=400「包含 4 条已入账记录」。
9. `POST /api/imports/{id}/rollback` → code=0；`GET /api/accounts` 断言余额全部还原；重复 rollback → 400「已撤销」。
10. rollback 后再次 preview → 重复标记消失（可重新导入）。
11. `GET /api/imports` 与 `GET /api/imports/{id}` 数据正确。
12. `GET /api/transactions/export` 含 alipay 来源行（已软删的不出现）。
13. 微信样例同样走一遍 preview（wechat 平台）确认解析正常。
14. 清理：mysql 删除 tester01 本次产生的 transaction_record、import_batch 行与测试账户（现金外），库终态回到 user 1 / account 1（现金）/ category 39 / txn 0 / batch 0。

- [ ] **Step 3: 前端构建**

Run: `cd frontend && npm run build`
Expected: 构建成功

- [ ] **Step 4: README 功能清单追加**

在「当前功能」列表的记账行后追加两行：

```markdown
- 导入：支付宝/微信 CSV 解析清洗（状态过滤/金额清洗）、分类自动映射、账户匹配、重复检测、预览勾选、批次入账与幂等撤销
- 批次：列表/明细查询，撤销后余额还原、可重新导入
```

- [ ] **Step 5: 提交 + 打 tag + 回收进程**

```bash
git add README.md
git -c user.name="李东升" -c user.email="lids@local" commit -m "docs(p3): README 功能清单更新"
git tag p3-import-v1
```

确认 tag 与分支 `p3-import` 不同名无歧义；回收后端进程（不杀 MySQL）。

- [ ] **Step 6: 汇报**

报告写 `.superpowers/sdd/task-10-report.md`（回归数、e2e 账户分配与余额表、库终态、tag）。向所有者汇报，等待阶段评审；评审通过后编写 P4（统计报表）计划。

---

## Self-Review 记录

- **规格覆盖**：UC-4.1/4.2/4.7（Task 5/8/9 上传+清洗+预览）、UC-4.3（Task 2/3 类型识别）、UC-4.4（Task 4 分类映射）、UC-4.5（Task 4 账户匹配）、UC-4.6（Task 5 重复检测）、UC-4.8（Task 6 单事务入账）、UC-4.9/4.10（Task 7 列表/明细）、UC-4.11（Task 7 幂等撤销）；§7.4（Task 5/6）、§7.5（Task 7）；§5.4 规则 4（不设唯一索引、只比未删除记录——Task 5/6 均按此实现）。P2 遗留 Minor 清偿（Task 1）。
- **占位符扫描**：无 TBD；所有代码步骤含完整代码；测试步骤含完整断言。Task 3/5 各有一处「先红后改」的断言修正，已用注意块明确标出正确值与理由。
- **类型一致性**：`ParsedRow`/`ImportPreviewRow`/`ImportRowRequest`/`ImportConfirmRequest`/`ImportBatchVo`/`ImportBatchDetailVo` 字段跨任务核对一致；`BillParser.platform()` 返回值（alipay/wechat）与流水 source 枚举、重复检测同源条件一致；`TransactionService.importRecord/createInternal/reverse/enrich(public)` 签名 Task 6/7 引用一致。
- **账务安全声明**：Task 6 的入账与 Task 7 的撤销均复用 `TransactionService.applyBalance` 单一抽象，计划中已用注释与评审提示双重强调禁止复制余额逻辑。
- **已知计划内简化**：账户匹配仅按账户名精确匹配 + 默认账户兜底（未做模糊/子类型推断，P5 可扩展）；分类映射为内置关键词表（可维护性已在类注释说明）；预览为无状态（不落库、不存服务端 session，前端持行直至确认）。
