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
