package com.balancewise.importbill;

import com.balancewise.importbill.parser.AlipayParser;
import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.BillParserSupport;
import com.balancewise.importbill.parser.ParsedRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝「电子客户回单」新版导出解析（依据真实导出文件结构建模）：
 * 前 13 行为说明+官方汇总行；退款是独立行（与微信的状态内嵌相反），需按订单号前缀回链原订单
 * 决定净额归属；收/支有「不计收支」口径（收益→收入，还款/提现/余额宝互转→transfer）。
 */
class AlipayRealFormatTest {

    private final AlipayParser parser = new AlipayParser();

    private List<ParsedRow> parseFixture() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-real-sample.csv").getFile().toPath());
        return parser.parse(BillParserSupport.readCsv(content)).getRows();
    }

    @Test
    void parsesOfficialReceiptStructure() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-real-sample.csv").getFile().toPath());
        BillParseResult result = parser.parse(BillParserSupport.readCsv(content));
        // 13 行：支出 2 + 收入 4（转账/收钱码/收益/部分退款回流）+ transfer 4（单次转入/还款/收款到余额宝/提现）= 10 笔保留
        // 丢弃 3：交易关闭 1、退款对应原单关闭 1、失败 1
        assertEquals(10, result.getRows().size());
        assertEquals(3, result.getDropped().size());
        assertTrue(result.getDropped().stream().anyMatch(d -> d.contains("交易关闭")));
        assertTrue(result.getDropped().stream().anyMatch(d -> d.contains("净额为 0")));
        assertTrue(result.getDropped().stream().anyMatch(d -> d.contains("失败")));
        // 官方汇总对账：全量行毛额与文件自带汇总一致
        assertNotNull(result.getOfficialCheck());
        assertTrue(result.getOfficialCheck().contains("核对一致"));
    }

    @Test
    void refundLinksToOriginalOrderAndReturnsToOriginalPayMethod() throws Exception {
        var rows = parseFixture();
        // 部分退款：原火车票 587 花呗 交易成功，退款 469.50 → 收入，且回流到原支付方式"花呗"
        ParsedRow refund = rows.stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("退款-火车票"))
                .findFirst().orElseThrow();
        assertEquals("income", refund.getType());
        assertEquals(0, new BigDecimal("469.50").compareTo(refund.getAmount()));
        assertEquals("花呗", refund.getPayMethod()); // 退款行本身支付方式为空，回链原订单取得
        // 原订单关闭的退款：不入账（净额 0）
        assertTrue(rows.stream().noneMatch(r -> r.getItem() != null && r.getItem().contains("退款-高德顺风车")));
    }

    @Test
    void neutralRowsResolveByItemKeywords() throws Exception {
        var rows = parseFixture();
        // 收益发放 → income；余额宝-单次转入 / 提现 / 收款到余额宝 → transfer
        ParsedRow yield = rows.stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("收益发放")).findFirst().orElseThrow();
        assertEquals("income", yield.getType());
        assertEquals(0, new BigDecimal("0.02").compareTo(yield.getAmount()));
        assertEquals(4, rows.stream().filter(r -> "transfer".equals(r.getType())).count());
        // 还款成功：转出账户=收/付款方式"余额宝"
        ParsedRow repay = rows.stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("信用卡还款")).findFirst().orElseThrow();
        assertEquals("transfer", repay.getType());
        assertEquals("余额宝", repay.getPayMethod());
    }

    @Test
    void regularIncomeAndExpenseByDirection() throws Exception {
        var rows = parseFixture();
        ParsedRow income = rows.stream()
                .filter(r -> r.getItem() != null && r.getItem().equals("转账"))
                .findFirst().orElseThrow();
        assertEquals("income", income.getType());
        assertEquals(0, new BigDecimal("15066.00").compareTo(income.getAmount()));
        ParsedRow expense = rows.stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("迅雷超级会员")).findFirst().orElseThrow();
        assertEquals("expense", expense.getType());
        assertEquals(LocalDateTime.of(2026, 11, 22, 15, 55, 27), expense.getOccurredAt());
    }
}
