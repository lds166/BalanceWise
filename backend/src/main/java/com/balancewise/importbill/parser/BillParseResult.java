package com.balancewise.importbill.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BillParseResult {
    private List<ParsedRow> rows = new ArrayList<>();
    /** 被丢弃行的原因描述，如 "第6行：状态=交易关闭" */
    private List<String> dropped = new ArrayList<>();
}
