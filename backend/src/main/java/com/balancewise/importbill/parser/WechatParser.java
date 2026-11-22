package com.balancewise.importbill.parser;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信账单解析（CSV 与官方导出 xlsx 共用，输入为二维行）。
 * 按首单元格 == "交易时间" 定位表头；状态列兼容 CSV 的「状态」与 xlsx 的「当前状态」；
 * 失败类状态（失败/关闭/已撤销/退款）丢弃，其余视为资金已成功转移——
 * 官方导出的成功态取值不止「支付成功」，还有「对方已收款/已存入零钱/充值成功/提现成功」等。
 * 类型识别：充值/提现/零钱通/转账类 → transfer；红包按收/支；其余消费类按收/支。
 */
@Component
public class WechatParser implements BillParser {

    /** 自有账户互转（官方"中性交易"：收/支列为 "/"）：零钱/零钱通/银行卡/信用卡还款之间的移动 */
    private static final java.util.Set<String> OWN_TRANSFER_TYPES = java.util.Set.of(
            "充值", "提现", "零钱通转入", "零钱通转出", "零钱充值", "零钱提现", "信用卡还款");

    @Override
    public String platform() {
        return "wechat";
    }

    private static final java.util.Set<String> FAILED_HINTS = java.util.Set.of("失败", "关闭", "已撤销", "退款", "退还");

    @Override
    public BillParseResult parse(List<List<String>> rows) {
        BillParseResult result = new BillParseResult();
        int headerIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && "交易时间".equals(rows.get(i).get(0))) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new com.balancewise.common.BizException("文件格式与所选平台不符：未找到微信表头");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> headers = rows.get(headerIdx);
        for (int i = 0; i < headers.size(); i++) {
            col.put(headers.get(i), i);
        }
        requireColumns(col, "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "交易单号");
        // 状态列：官方 xlsx 叫「当前状态」，历史 CSV 叫「状态」
        String statusCol = col.containsKey("当前状态") ? "当前状态" : "状态";
        if (!col.containsKey(statusCol)) {
            throw new com.balancewise.common.BizException("文件格式与所选平台不符：缺少列「当前状态」");
        }

        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            int lineNo = i + 1;
            String status = cell(cells, col, statusCol);
            if (FAILED_HINTS.stream().anyMatch(status::contains)) {
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
        // 退款/退还类（转账-退款、商户-退款）：钱回到自己账户 → 收入
        if (tradeType.contains("退款") || tradeType.contains("退还")) {
            return "income";
        }
        if (isTransferType(tradeType)) {
            return "transfer";
        }
        return "收入".equals(direction) ? "income" : "expense";
    }

    /** 自有账户互转识别：精确名 + 官方 xlsx 的带后缀变体（如「转入零钱通-来自农业银行(4271)」「零钱通转出-到零钱」）。
     *  注意「转账」（微信用户间转账）不属于此类——给别人/收别人款是对外收支，按收/支方向归类。 */
    private boolean isTransferType(String tradeType) {
        return OWN_TRANSFER_TYPES.contains(tradeType)
                || tradeType.contains("零钱通")
                || tradeType.contains("零钱充值")
                || tradeType.contains("零钱提现");
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
