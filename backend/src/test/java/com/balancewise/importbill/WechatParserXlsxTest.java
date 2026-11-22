package com.balancewise.importbill;

import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.BillParserSupport;
import com.balancewise.importbill.parser.ParsedRow;
import com.balancewise.importbill.parser.WechatParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信官方导出 xlsx 解析（依据真实导出文件结构建模）：
 * 前 17 行为说明文字，表头第 18 行起；日期为日期型单元格、金额为 ¥#,##0.00 数值格式；
 * 状态列叫「当前状态」且取值远不止「支付成功」（已转账/已转入零钱通/对方已收钱/已退款¥0.37…）。
 */
class WechatParserXlsxTest {

    private final WechatParser parser = new WechatParser();

    /** 按官方导出结构构造 xlsx：说明行若干 + 表头 + 数据行 */
    private byte[] buildOfficialXlsx() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        var sheet = wb.createSheet("Sheet1");
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        CellStyle amountStyle = wb.createCellStyle();
        amountStyle.setDataFormat(wb.createDataFormat().getFormat("¥#,##0.00"));

        // 说明文字（真实文件前 17 行均为说明）
        for (int i = 0; i < 3; i++) {
            sheet.createRow(i).createCell(0).setCellValue("微信支付账单明细说明" + i);
        }
        // 表头（第 18 行位置语义上即第 4 行）
        String[] headers = {"交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"};
        Row head = sheet.createRow(3);
        for (int i = 0; i < headers.length; i++) {
            head.createCell(i).setCellValue(headers[i]);
        }

        Object[][] data = {
                // 交易时间, 交易类型, 对方, 商品, 收/支, 金额, 支付方式, 当前状态
                {LocalDateTime.of(2026, 11, 22, 15, 15, 5), "商户消费", "永辉超市", "生鲜食品", "支出", 45.50, "零钱通", "支付成功"},
                {LocalDateTime.of(2026, 11, 21, 20, 0, 0), "转账", "张三", "转账备注:微信转账", "收入", 1850.00, "/", "对方已收钱"},
                {LocalDateTime.of(2026, 11, 20, 9, 0, 0), "转入零钱通-来自农业银行(4271)", "农业银行(4271)", "/", "/", 5000.00, "/", "已转入零钱通"},
                {LocalDateTime.of(2026, 11, 19, 12, 0, 0), "信用卡还款", "招商银行信用卡还款", "/", "/", 20020.00, "零钱通", "支付成功"},
                {LocalDateTime.of(2026, 11, 18, 8, 0, 0), "商户消费", "某商户", "部分退款商品", "支出", 3.99, "零钱", "已退款¥0.37"},
                {LocalDateTime.of(2026, 11, 17, 8, 0, 0), "商户消费", "某商户", "全额退款商品", "支出", 9.90, "零钱", "已全额退款"},
                {LocalDateTime.of(2026, 11, 16, 8, 0, 0), "转账", "李四", "转账-退还", "收入", 66.00, "零钱", "对方已退还"},
                {LocalDateTime.of(2026, 11, 15, 8, 0, 0), "商户消费", "某商户", "支付失败商品", "支出", 1.00, "零钱", "支付失败"},
        };
        int rowIdx = 4;
        for (Object[] d : data) {
            Row r = sheet.createRow(rowIdx++);
            Cell dateCell = r.createCell(0);
            dateCell.setCellValue((LocalDateTime) d[0]);
            dateCell.setCellStyle(dateStyle);
            r.createCell(1).setCellValue((String) d[1]);
            r.createCell(2).setCellValue((String) d[2]);
            r.createCell(3).setCellValue((String) d[3]);
            r.createCell(4).setCellValue((String) d[4]);
            Cell amt = r.createCell(5);
            amt.setCellValue((Double) d[5]);
            amt.setCellStyle(amountStyle);
            r.createCell(6).setCellValue((String) d[6]);
            r.createCell(7).setCellValue((String) d[7]);
            r.createCell(8).setCellValue("4200000001202611" + String.format("%04d", rowIdx));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    @Test
    void parsesOfficialXlsxStructure() {
        BillParseResult result = parser.parse(BillParserSupport.readExcel(buildXlsx()));
        // 8 行数据 → 7 笔流水：部分退款行拆成 原支出+退款收入 两笔；全额退款/支付失败丢弃
        assertEquals(7, result.getRows().size());
        assertEquals(2, result.getDropped().size());
        assertTrue(result.getDropped().stream().anyMatch(d -> d.contains("已全额退款")));
        assertTrue(result.getDropped().stream().anyMatch(d -> d.contains("支付失败")));
    }

    @Test
    void partialRefundKeepsBothLegsSoBalanceStaysFlat() {
        var rows = parser.parse(BillParserSupport.readExcel(buildXlsx())).getRows();
        // 部分退款：原支出 3.99 保留 + 退款收入 0.37，两笔都记账（净 -3.62，账平）
        ParsedRow original = rows.stream().filter(r -> r.getItem() != null && r.getItem().contains("部分退款¥0.37")).findFirst().orElseThrow();
        assertEquals("expense", original.getType());
        assertEquals(0, new BigDecimal("3.99").compareTo(original.getAmount()));
        ParsedRow back = rows.stream().filter(r -> r.getExternalId() != null && r.getExternalId().endsWith("-RF")).findFirst().orElseThrow();
        assertEquals("income", back.getType());
        assertEquals(0, new BigDecimal("0.37").compareTo(back.getAmount()));
        assertEquals("退款到账（已退款¥0.37）", back.getItem());
    }

    @Test
    void xlsxDateCellAndAmountFormatAreParsed() {
        ParsedRow first = parser.parse(BillParserSupport.readExcel(buildXlsx())).getRows().get(0);
        assertEquals(LocalDateTime.of(2026, 11, 22, 15, 15, 5), first.getOccurredAt()); // 日期型单元格
        assertEquals(0, new BigDecimal("45.50").compareTo(first.getAmount()));          // ¥#,##0.00 数值格式
        assertEquals("expense", first.getType());
        assertEquals("零钱通", first.getPayMethod());
    }

    @Test
    void xlsxTransferVariantsResolveToTransfer() {
        var rows = parser.parse(BillParserSupport.readExcel(buildXlsx())).getRows();
        assertEquals("income", rows.get(1).getType()); // 用户间转账收款是对外收入
        assertEquals("transfer", rows.get(2).getType()); // 转入零钱通-来自农业银行(4271)，收/支为 "/"
        assertEquals(0, new BigDecimal("20020.00").compareTo(rows.get(3).getAmount()));
        assertEquals("transfer", rows.get(3).getType()); // 信用卡还款
    }

    private byte[] buildXlsx() {
        try {
            return buildOfficialXlsx();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
