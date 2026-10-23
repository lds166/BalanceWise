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
