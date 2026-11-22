package com.balancewise.importbill.parser;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信账单解析（CSV 与官方导出 xlsx 共用，输入为二维行）。
 * 按首单元格 == "交易时间" 定位表头；状态列兼容 CSV 的「状态」与 xlsx 的「当前状态」。
 * 状态语义按"每一笔资金移动都要有账"处理（账平原则）：
 * - 成功态（支付成功/已转账/对方已收钱/已转入零钱通/资金已到账/充值完成/提现已到账等）正常入账；
 * - 部分退款（状态含退款金额，如「已退款¥0.37」）：原支出保留 + 生成一笔退款收入，两笔都记；
 * - 已全额退款：净额为 0，不入账（丢弃并注明原因）；
 * - 对方已退还且收/支=收入：退款到账，正常入账；收/支=支出：转出的钱被退回，净额 0 不入账；
 * - 失败/关闭/已撤销：资金未移动，不入账。
 * 类型识别：退款/退还类 → 收入；自有账户互转（零钱通/零钱充值/零钱提现/信用卡还款）→ transfer；
 * 微信用户间「转账」是对外收支，按收/支方向归类。
 */
@Component
public class WechatParser implements BillParser {

    /** 自有账户互转（官方"中性交易"：收/支列为 "/"）：零钱/零钱通/银行卡/信用卡还款之间的移动 */
    private static final java.util.Set<String> OWN_TRANSFER_TYPES = java.util.Set.of(
            "充值", "提现", "零钱通转入", "零钱通转出", "零钱充值", "零钱提现", "信用卡还款");

    /** 资金未移动的状态（不入账） */
    private static final java.util.Set<String> FAILED_HINTS = java.util.Set.of("失败", "关闭", "已撤销");

    /** 部分退款金额提取：「已退款¥0.37」「已退款(¥0.45)」 */
    private static final java.util.regex.Pattern REFUND_AMOUNT =
            java.util.regex.Pattern.compile("退款[（(]?[¥￥]([0-9.]+)");

    @Override
    public String platform() {
        return "wechat";
    }

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

        // 官方汇总对账（第二道防线）：xlsx 表头前的"收入：20笔 44100.21元"等汇总行是官方自算结果，
        // 与全部数据行（含被丢弃的退款/失败行）按收/支方向的毛额累计比对——丢任何一行都会在这里现形
        long[] official = BillParserSupport.extractOfficialSummary(rows, headerIdx);
        java.util.concurrent.atomic.AtomicInteger grossIncomeCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger grossExpenseCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger grossTransferCount = new java.util.concurrent.atomic.AtomicInteger();
        java.math.BigDecimal[] grossAmt = new java.math.BigDecimal[]{java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO};

        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            int lineNo = i + 1;
            String status = cell(cells, col, statusCol);
            try {
                ParsedRow row = buildRow(cells, col, lineNo);
                String direction = cell(cells, col, "收/支");
                // 毛额累计（含即将被丢弃的行）：对账基准是"账单里出现过的每一笔"
                int bucket = "收入".equals(direction) ? 0 : "支出".equals(direction) ? 1 : 2;
                grossAmt[bucket] = grossAmt[bucket].add(row.getAmount());
                if (bucket == 0) grossIncomeCount.incrementAndGet();
                else if (bucket == 1) grossExpenseCount.incrementAndGet();
                else grossTransferCount.incrementAndGet();

                // 1) 全额退款：钱出又全额回，净额 0，不入账（余额不受影响）
                if (status.contains("已全额退款")) {
                    result.getDropped().add("第" + lineNo + "行：已全额退款（净额为 0），不入账");
                    continue;
                }
                // 2) 部分退款（状态带退款金额）：原支出保留 + 退款金额单独记一笔回流，两笔都记账才平
                java.util.regex.Matcher refund = REFUND_AMOUNT.matcher(status);
                if (refund.find()) {
                    BigDecimal back = BillParserSupport.parseAmount(refund.group(1));
                    row.setItem((row.getItem() == null ? "" : row.getItem()) + "（部分退款¥" + back.toPlainString() + "）");
                    result.getRows().add(row);
                    ParsedRow refundRow = new ParsedRow();
                    refundRow.setLineNo(lineNo);
                    refundRow.setExternalId(row.getExternalId() + "-RF");
                    refundRow.setOccurredAt(row.getOccurredAt());
                    refundRow.setCounterparty(row.getCounterparty());
                    refundRow.setItem("退款到账（" + status + "）");
                    refundRow.setAmount(back);
                    refundRow.setPlatformCategory(row.getPlatformCategory());
                    refundRow.setPayMethod(row.getPayMethod());
                    // 退款回流方向与原交易相反：原支出→退款收入；原收入（收款被退回）→退款支出
                    refundRow.setType("收入".equals(direction) ? "expense" : "income");
                    result.getRows().add(refundRow);
                    continue;
                }
                // 3) 对方退还：收/支=收入 表示退款到账，正常入账；收/支=支出 表示转出的钱被退回，净额 0 不入账
                if (status.contains("退还")) {
                    if ("收入".equals(direction)) {
                        result.getRows().add(row);
                    } else {
                        result.getDropped().add("第" + lineNo + "行：已退还（净额为 0），不入账");
                    }
                    continue;
                }
                // 4) 其他退款状态且未标金额：按全额退款处理（净额 0）
                if (status.contains("退款")) {
                    result.getDropped().add("第" + lineNo + "行：" + status + "（净额为 0），不入账");
                    continue;
                }
                // 5) 失败/关闭/已撤销：资金未移动
                if (FAILED_HINTS.stream().anyMatch(status::contains)) {
                    result.getDropped().add("第" + lineNo + "行：状态=" + status);
                    continue;
                }
                result.getRows().add(row);
            } catch (RuntimeException e) {
                result.getDropped().add("第" + lineNo + "行：解析失败（" + e.getMessage() + "）");
            }
        }
        result.setOfficialCheck(BillParserSupport.reconcileOfficial(official,
                new long[]{grossIncomeCount.get(), grossExpenseCount.get(), grossTransferCount.get()}, grossAmt, true));
        return result;
    }

    private ParsedRow buildRow(List<String> cells, Map<String, Integer> col, int lineNo) {
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
        return row;
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
