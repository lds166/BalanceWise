package com.balancewise.importbill.vo;

import com.balancewise.importbill.dto.ImportPreviewRow;
import lombok.Data;

import java.util.List;

@Data
public class ImportPreviewVo {
    private String platform;
    private String fileName;
    private List<ImportPreviewRow> rows;
    private List<String> dropped;
    /** 非阻断性提示（如 AI 分类降级/截断说明），前端以 info 条展示 */
    private String notice;
}
