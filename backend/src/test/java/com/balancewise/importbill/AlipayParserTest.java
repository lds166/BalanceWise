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

class AlipayParserTest {

    private final AlipayParser parser = new AlipayParser();

    @Test
    void parsesValidRowsAndDropsFailedTransactions() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        BillParseResult result = parser.parse(BillParserSupport.readCsv(content));

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
        ParsedRow quoted = parser.parse(BillParserSupport.readCsv(content)).getRows().stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("iPad"))
                .findFirst().orElseThrow();
        assertEquals("iPad, 保护壳", quoted.getItem());
    }

    @Test
    void typeRecognition() throws Exception {
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        List<ParsedRow> rows = parser.parse(BillParserSupport.readCsv(content)).getRows();
        // 交易付款→支出(expense)，退款→收入(income)，余额宝转入→转账(transfer)
        assertEquals("expense", rows.get(0).getType());
        assertEquals("income", rows.get(3).getType());
        assertEquals("transfer", rows.get(2).getType());
    }
}
