package com.balancewise.importbill.parser;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class BillParserSupport {

    public static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BillParserSupport() {
    }

    /** UTF-8 严格解码，失败回退 GBK（支付宝历史导出为 GBK） */
    public static String decode(byte[] content) {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = utf8.decode(ByteBuffer.wrap(content));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return new String(content, java.nio.charset.Charset.forName("GBK"));
        }
    }

    /** 按行切分（支持 \n 与 \r\n），去掉 BOM */
    public static List<String> lines(String text) {
        String clean = text.startsWith("﻿") ? text.substring(1) : text;
        List<String> out = new ArrayList<>();
        for (String line : clean.split("\r?\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** 字节内容 → 二维行（CSV：解码 + 逐行切分） */
    public static List<List<String>> readCsv(byte[] content) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines(decode(content))) {
            rows.add(splitCsvLine(line));
        }
        return rows;
    }

    /** 字节内容 → 二维行（Excel：首张工作表，DataFormatter 取显示值），全空行跳过。
     *  日期型单元格不依赖其显示格式，统一输出 yyyy-MM-dd HH:mm:ss（防官方改显示格式）。 */
    public static List<List<String>> readExcel(byte[] content) {
        List<List<String>> rows = new ArrayList<>();
        org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
        try (org.apache.poi.ss.usermodel.Workbook wb =
                     org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(content))) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                List<String> cells = new ArrayList<>();
                boolean any = false;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String v;
                    if (cell == null) {
                        v = "";
                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                        v = cell.getLocalDateTimeCellValue().format(DT);
                    } else {
                        v = formatter.formatCellValue(cell).trim();
                    }
                    if (!v.isEmpty()) {
                        any = true;
                    }
                    cells.add(v);
                }
                if (any) {
                    rows.add(cells);
                }
            }
        } catch (java.io.IOException e) {
            throw new com.balancewise.common.BizException("无法读取 Excel 文件：" + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new com.balancewise.common.BizException("Excel 文件中没有数据行");
        }
        return rows;
    }

    /** CSV 行切分：支持 "..." 包裹、内嵌逗号与 "" 转义引号 */
    public static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
            } else if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /**
     * 金额清洗：去 ¥ ￥ 元 与空白；千分位逗号仅允许标准形式（1,234.50），
     * 畸形 "1,2" 必须拒绝（台账 #9：曾静默去逗号变成 12）。
     */
    public static BigDecimal parseAmount(String raw) {
        String s = raw == null ? "" : raw
                .replace("¥", "").replace("￥", "").replace("元", "").trim();
        if (s.contains(",")) {
            if (!s.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
                throw new IllegalArgumentException("金额格式不正确：" + raw);
            }
            s = s.replace(",", "");
        }
        return new BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static LocalDateTime parseTime(String raw) {
        return LocalDateTime.parse(raw.trim(), DT);
    }

    /**
     * 提取表头前的官方汇总行（微信 xlsx / 支付宝电子回单自带），返回
     * [收入笔数, 收入额(分), 支出笔数, 支出额(分), 中性笔数, 中性额(分)]；无汇总行返回 null。
     * 支持「收入/支出/中性交易/不计收支」四种口径（微信叫中性交易，支付宝叫不计收支）。
     */
    public static long[] extractOfficialSummary(List<List<String>> rows, int headerIdx) {
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("^(收入|支出|中性交易|不计收支)：(\\d+)笔\\s*([\\d,]+\\.?\\d*)元");
        long[] out = null;
        for (int i = 0; i < headerIdx; i++) {
            String line = rows.get(i).isEmpty() ? "" : rows.get(i).get(0);
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.matches()) {
                continue;
            }
            if (out == null) {
                out = new long[6];
            }
            int idx = "收入".equals(m.group(1)) ? 0 : "支出".equals(m.group(1)) ? 2 : 4;
            out[idx] = Long.parseLong(m.group(2));
            out[idx + 1] = new BigDecimal(m.group(3).replace(",", "")).movePointRight(2).longValue();
        }
        return out;
    }

    /** 官方汇总 vs 全量数据行毛额（含被丢弃行）逐项比对；金额严格模式（微信：汇总=行累加）不一致返回告警 */
    public static String reconcileOfficial(long[] official, long[] counts, BigDecimal[] grossAmt, boolean amountStrict) {
        if (official == null) {
            return null;
        }
        String[] names = {"收入", "支出", "中性/不计收支"};
        java.util.List<String> bad = new ArrayList<>();
        java.util.List<String> info = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long parsedAmt = grossAmt[i].movePointRight(2).longValue();
            if (counts[i] != official[i * 2]) {
                bad.add(names[i] + "解析 " + counts[i] + "笔 vs 官方 " + official[i * 2] + "笔");
                continue;
            }
            if (parsedAmt != official[i * 2 + 1]) {
                String diff = names[i] + "金额明细累加 " + grossAmt[i] + "元 vs 官方统计 "
                        + official[i * 2 + 1] / 100.0 + "元（差 " + (parsedAmt - official[i * 2 + 1]) / 100.0 + "元）";
                if (amountStrict) {
                    bad.add(diff);
                } else {
                    info.add(diff + "，属官方统计口径差异（立减等优惠抵扣），以实际交易金额为准");
                }
            }
        }
        if (bad.isEmpty()) {
            return "已与官方汇总核对一致（" + names[0] + " " + counts[0] + "笔、"
                    + names[1] + " " + counts[1] + "笔、" + names[2] + " " + counts[2] + "笔），无漏行"
                    + (info.isEmpty() ? "" : "；" + String.join("；", info));
        }
        return "警告：解析结果与官方汇总不一致——" + String.join("；", bad) + "，请确认文件完整或联系排查";
    }
}
