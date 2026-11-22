package com.balancewise.importbill.parser;

import java.util.List;

/**
 * 平台账单解析器（按平台路由）。输入为统一的二维行模型——
 * 文件字节 → 行的读取（CSV / XLSX）由 BillParserSupport 与 ImportService 按扩展名分流，
 * 解析器只关心表头定位与字段映射，新增文件格式不需要改平台解析器。
 */
public interface BillParser {

    /** alipay / wechat，必须落在流水 source 枚举内 */
    String platform();

    /** 解析二维行：定位表头 → 字段映射 → 清洗，返回标准化行 + 丢弃原因 */
    BillParseResult parse(List<List<String>> rows);
}
