package com.balancewise.importbill.parser;

import com.balancewise.common.BizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝账单解析，双格式支持（输入为二维行）：
 *
 * 【新版·电子客户回单】（网页导出，列：交易时间/交易分类/交易对方/对方账号/商品说明/收/支/金额/
 * 收/付款方式/交易状态/交易订单号/商家订单号/备注）——按首单元格=="交易时间"定位表头。
 * 状态语义按"每一笔资金移动都要有账"处理：
 * - 交易成功：支出→expense、收入→income、不计收支→按商品/分类识别（收益/奖励→收入，
 *   还款/提现/余额宝及余利宝互转→transfer）；
 * - 退款成功：独立退款行。按交易订单号前缀回链原订单——原订单交易成功则记一笔退款收入
 *   （回流到原支付方式账户）；原订单交易关闭/失败则净额为 0 不入账；找不到原订单仍记收入（资金确已回流）；
 * - 交易关闭/失败：资金未移动，不入账；
 * - 还款成功：自有账户互转 → transfer（转出账户=收/付款方式，转入的卡在预览页补选）。
 *
 * 【旧版·App 导出】（列：交易号/交易创建时间/类型/对方/商品名称/金额（元）/收/支/状态/付款方式）
 * ——按首单元格=="交易号"定位表头；仅保留 状态=交易成功；类型映射：退款→收入、
 * 余额宝转入/转出/花呗还款→transfer，其余按收/支。
 */
@Component
public class AlipayParser implements BillParser {

    /** 旧版类型映射 */
    private static final Map<String, String> TYPE_MAP = Map.of(
            "退款", "income",
            "余额宝转入", "transfer",
            "余额宝转出", "transfer",
            "花呗还款", "transfer");

    @Override
    public String platform() {
        return "alipay";
    }

    @Override
    public BillParseResult parse(List<List<String>> rows) {
        int headerIdx = -1;
        boolean newFormat = false;
        for (int i = 0; i < rows.size(); i++) {
            String first = rows.get(i).isEmpty() ? "" : rows.get(i).get(0);
            if ("交易时间".equals(first)) {
                headerIdx = i;
                newFormat = true;
                break;
            }
            if ("交易号".equals(first)) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new BizException("文件格式与所选平台不符：未找到支付宝表头");
        }
        return newFormat ? parseNewFormat(rows, headerIdx) : parseLegacyFormat(rows, headerIdx);
    }

    // ---------------------------------------------------------------- 新版（电子客户回单）

    private BillParseResult parseNewFormat(List<List<String>> rows, int headerIdx) {
        BillParseResult result = new BillParseResult();
        Map<String, Integer> col = columnMap(rows.get(headerIdx));
        requireColumns(col, "交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额",
                "收/付款方式", "交易状态", "交易订单号");
        long[] official = BillParserSupport.extractOfficialSummary(rows, headerIdx);

        // 第一遍：登记非退款行的 订单号 → [收/付款方式, 交易状态]，供退款行回链原订单
        Map<String, String[]> originals = new HashMap<>();
        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            String status = cell(cells, col, "交易状态");
            String orderId = cell(cells, col, "交易订单号");
            boolean isRefundRow = "退款成功".equals(status) || "退款".equals(cell(cells, col, "交易分类"));
            if (!isRefundRow && !orderId.isBlank() && !status.isBlank()) {
                originals.put(orderId, new String[]{cell(cells, col, "收/付款方式"), status});
            }
        }

        // 毛额累计（含被丢弃行）：对账基准是"账单里出现过的每一笔"，按收/支原始口径分桶
        long[] counts = new long[3];
        BigDecimal[] gross = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};

        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            int lineNo = i + 1;
            String status = cell(cells, col, "交易状态");
            String direction = cell(cells, col, "收/支");
            try {
                ParsedRow row = buildNewRow(cells, col, lineNo);
                int bucket = "收入".equals(direction) ? 0 : "支出".equals(direction) ? 1 : 2;
                gross[bucket] = gross[bucket].add(row.getAmount());
                counts[bucket]++;

                // 退款成功：按订单号前缀回链原订单，决定净额归属
                if ("退款成功".equals(status) || "退款".equals(cell(cells, col, "交易分类"))) {
                    String[] orig = findOriginal(originals, cell(cells, col, "交易订单号"));
                    if (orig != null && !"交易成功".equals(orig[1])) {
                        // 原订单关闭/失败：退款把未发生的支付撤回，净额为 0
                        result.getDropped().add("第" + lineNo + "行：退款对应原订单已" + orig[1] + "（净额为 0），不入账");
                        continue;
                    }
                    // 原订单交易成功（部分退款）或找不到原订单：退款是真实回流 → 记收入
                    row.setType("income");
                    if (orig != null) {
                        row.setPayMethod(orig[0]); // 退款回到原支付方式（如花呗）
                    }
                    result.getRows().add(row);
                    continue;
                }
                // 交易关闭/失败：资金未移动
                if ("交易关闭".equals(status) || "失败".equals(status)) {
                    result.getDropped().add("第" + lineNo + "行：状态=" + status);
                    continue;
                }
                // 还款成功：自有账户互转（转出=收/付款方式，转入的信用卡/花呗在预览页补选）
                if ("还款成功".equals(status)) {
                    row.setType("transfer");
                    result.getRows().add(row);
                    continue;
                }
                // 交易成功（及其他视为成功的状态）：按收/支归类，不计收支按商品/分类识别
                row.setType(resolveNewType(direction, cell(cells, col, "交易分类"), cell(cells, col, "商品说明")));
                result.getRows().add(row);
            } catch (RuntimeException e) {
                result.getDropped().add("第" + lineNo + "行：解析失败（" + e.getMessage() + "）");
            }
        }
        result.setOfficialCheck(BillParserSupport.reconcileOfficial(official, counts, gross, false));
        return result;
    }

    private ParsedRow buildNewRow(List<String> cells, Map<String, Integer> col, int lineNo) {
        ParsedRow row = new ParsedRow();
        row.setLineNo(lineNo);
        row.setExternalId(cell(cells, col, "交易订单号"));
        row.setOccurredAt(BillParserSupport.parseTime(cell(cells, col, "交易时间")));
        row.setCounterparty(cell(cells, col, "交易对方"));
        row.setItem(cell(cells, col, "商品说明"));
        row.setAmount(BillParserSupport.parseAmount(cell(cells, col, "金额")));
        row.setPlatformCategory(cell(cells, col, "交易分类"));
        row.setPayMethod(cell(cells, col, "收/付款方式"));
        return row;
    }

    /** 退款行回链：取 交易订单号 是退款单号前缀且最长的原订单 */
    private String[] findOriginal(Map<String, String[]> originals, String refundOrderId) {
        String[] hit = null;
        int best = 0;
        for (Map.Entry<String, String[]> e : originals.entrySet()) {
            if (!refundOrderId.isBlank() && refundOrderId.startsWith(e.getKey()) && e.getKey().length() > best) {
                best = e.getKey().length();
                hit = e.getValue();
            }
        }
        return hit;
    }

    /** 不计收支行的类型识别：收益/奖励是真实回流 → 收入；还款/提现/余额宝及余利宝互转 → transfer */
    private String resolveNewType(String direction, String tradeType, String item) {
        if ("收入".equals(direction)) {
            return "income";
        }
        if ("支出".equals(direction)) {
            return "expense";
        }
        String text = (tradeType == null ? "" : tradeType) + " " + (item == null ? "" : item);
        if (text.contains("收益") || text.contains("奖励")) {
            return "income";
        }
        return "transfer"; // 还款/提现/余额宝与余利宝互转等自有账户移动
    }

    // ---------------------------------------------------------------- 旧版（App 导出）

    private BillParseResult parseLegacyFormat(List<List<String>> rows, int headerIdx) {
        BillParseResult result = new BillParseResult();
        Map<String, Integer> col = columnMap(rows.get(headerIdx));
        requireColumns(col, "交易号", "交易创建时间", "类型", "对方", "商品名称", "金额（元）", "收/支", "状态", "付款方式");

        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
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
                String type = TYPE_MAP.getOrDefault(cell(cells, col, "类型"),
                        "收入".equals(cell(cells, col, "收/支")) ? "income" : "expense");
                row.setType(type);
                result.getRows().add(row);
            } catch (RuntimeException e) {
                result.getDropped().add("第" + lineNo + "行：解析失败（" + e.getMessage() + "）");
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 公共

    private Map<String, Integer> columnMap(List<String> headers) {
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            col.put(headers.get(i), i);
        }
        return col;
    }

    private void requireColumns(Map<String, Integer> col, String... names) {
        for (String name : names) {
            if (!col.containsKey(name)) {
                throw new BizException("文件格式与所选平台不符：缺少列「" + name + "」");
            }
        }
    }

    private String cell(List<String> cells, Map<String, Integer> col, String name) {
        int idx = col.get(name);
        return idx < cells.size() ? cells.get(idx) : "";
    }
}
