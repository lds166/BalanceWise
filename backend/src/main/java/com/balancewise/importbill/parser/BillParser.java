package com.balancewise.importbill.parser;

public interface BillParser {

    /** alipay / wechat，必须落在流水 source 枚举内 */
    String platform();

    /** 解析账单文件字节内容（UTF-8 优先，失败回退 GBK），清洗后返回标准化行 */
    BillParseResult parse(byte[] content);
}
