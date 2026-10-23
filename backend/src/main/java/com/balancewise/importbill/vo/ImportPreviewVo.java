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
}
