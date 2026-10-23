package com.balancewise.importbill.vo;

import com.balancewise.record.vo.TransactionVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ImportBatchDetailVo extends ImportBatchVo {
    private List<TransactionVo> records;
}
