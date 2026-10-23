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
}
