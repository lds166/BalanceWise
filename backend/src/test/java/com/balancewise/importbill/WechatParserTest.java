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
        assertEquals("transfer", rows.get(1).getType()); // 转账收款 → transfer
        assertEquals("transfer", rows.get(2).getType()); // 充值 → transfer
    }
}
