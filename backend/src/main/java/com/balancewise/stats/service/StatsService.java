package com.balancewise.stats.service;

import com.balancewise.stats.mapper.StatsMapper;
import com.balancewise.stats.mapper.row.TypeSumRow;
import com.balancewise.stats.vo.StatsSummaryVo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计报表（UC-5.x）。纯只读：绝不写账务数据、绝不触碰 account.balance、绝不做余额联动。
 * 时间边界与 P2 TransactionService.buildWrapper 完全一致。
 */
@Service
public class StatsService {

    private final StatsMapper statsMapper;

    public StatsService(StatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    /** 区间汇总（UC-5.2）：start/end 为空 = 全部时间（上界取明天，保证含今天全部）；balance = income - expense */
    public StatsSummaryVo summary(Long userId, LocalDate start, LocalDate end, Long accountId) {
        List<TypeSumRow> rows = statsMapper.sumByType(userId, fromOf(start), toOf(end), accountId);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (TypeSumRow r : rows) {
            if ("income".equals(r.getType())) {
                income = income.add(r.getTotal());
            } else if ("expense".equals(r.getType())) {
                expense = expense.add(r.getTotal());
            }
        }
        StatsSummaryVo vo = new StatsSummaryVo();
        vo.setIncome(income.setScale(2, RoundingMode.HALF_UP));
        vo.setExpense(expense.setScale(2, RoundingMode.HALF_UP));
        vo.setBalance(income.subtract(expense).setScale(2, RoundingMode.HALF_UP));
        return vo;
    }

    /** start 含当天 00:00:00；null = 无下界 */
    private LocalDateTime fromOf(LocalDate start) {
        return start != null ? start.atStartOfDay() : null;
    }

    /** end 排他上界 < 次日 00:00:00（含 end 当天全部）；null = 含至今全部 */
    private LocalDateTime toOf(LocalDate end) {
        return end != null ? end.plusDays(1).atStartOfDay() : LocalDateTime.now().plusDays(1);
    }
}
