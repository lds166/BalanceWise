package com.balancewise.importbill.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BillParseResult {
    private List<ParsedRow> rows = new ArrayList<>();
    /** 被丢弃行的原因描述，如 "第6行：状态=交易关闭" */
    private List<String> dropped = new ArrayList<>();
    /**
     * 与官方汇总的对账结果（微信 xlsx 自带"收入：20笔 44100.21元"等汇总行）：
     * 一致 → 核对确认文案；不一致 → 告警文案；文件无汇总行 → null。
     */
    private String officialCheck;
}
