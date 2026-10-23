package com.balancewise.importbill.parser;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝账单 CSV 解析。
 * 表头前 4 行为说明（"支付宝交易记录明细查询"等），解析器按首行单元格 == "交易号" 定位表头。
 * 仅保留 状态=交易成功 的记录；类型识别：退款→收入，余额宝/转账类→转账，其余按 收/支。
 */
@Component
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
