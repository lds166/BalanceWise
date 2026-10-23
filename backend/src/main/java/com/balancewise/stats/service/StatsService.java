package com.balancewise.stats.service;

import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.stats.dto.StatsCategoryQuery;
import com.balancewise.stats.dto.StatsTrendQuery;
import com.balancewise.stats.mapper.StatsMapper;
import com.balancewise.stats.mapper.row.CategorySumRow;
import com.balancewise.stats.mapper.row.DailyFlowRow;
import com.balancewise.stats.mapper.row.TypeSumRow;
import com.balancewise.stats.vo.CategoryStatItemVo;
import com.balancewise.stats.vo.NetWorthPointVo;
import com.balancewise.stats.vo.StatsCategoryVo;
import com.balancewise.stats.vo.StatsNetWorthVo;
import com.balancewise.stats.vo.StatsSummaryVo;
import com.balancewise.stats.vo.StatsTrendVo;
import com.balancewise.stats.vo.TrendPointVo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计报表（UC-5.x）。纯只读：绝不写账务数据、绝不触碰 account.balance、绝不做余额联动。
 * 时间边界与 P2 TransactionService.buildWrapper 完全一致。
 */
@Service
public class StatsService {

    private final StatsMapper statsMapper;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;

    public StatsService(StatsMapper statsMapper, AccountMapper accountMapper, CategoryMapper categoryMapper) {
        this.statsMapper = statsMapper;
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
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

    /**
     * 收支趋势（UC-5.3）。month：按月（近 months 个月含当月，或 start~end 覆盖的月份逐一补零）；
     * day：按日（必须 start+end，跨度 ≤ 366 天）。每期 income/expense 聚合、balance = income - expense。
     */
    public StatsTrendVo trend(Long userId, StatsTrendQuery q) {
        Range range = resolveRange(q);
        List<DailyFlowRow> flows = statsMapper.dailyFlows(userId, range.from(), range.to(), q.getAccountId());
        StatsTrendVo vo = new StatsTrendVo();
        List<TrendPointVo> points = new ArrayList<>();
        for (String label : bucketLabels(range)) {
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            for (DailyFlowRow f : flows) {
                boolean inBucket = "month".equals(range.groupBy())
                        ? YearMonth.from(f.getDay()).equals(YearMonth.parse(label))
                        : f.getDay().equals(LocalDate.parse(label));
                if (inBucket) {
                    if ("income".equals(f.getType())) {
                        income = income.add(f.getTotal());
                    } else {
                        expense = expense.add(f.getTotal());
                    }
                }
            }
            TrendPointVo p = new TrendPointVo();
            p.setLabel(label);
            p.setIncome(income.setScale(2, RoundingMode.HALF_UP));
            p.setExpense(expense.setScale(2, RoundingMode.HALF_UP));
            p.setBalance(income.subtract(expense).setScale(2, RoundingMode.HALF_UP));
            points.add(p);
        }
        vo.setPoints(points);
        return vo;
    }

    /** 解析趋势区间：groupBy 校验 + 区间推导（month 快捷 = 近 months 个月含当月；显式 start/end 覆盖） */
    private Range resolveRange(StatsTrendQuery q) {
        String groupBy = q.getGroupBy() == null || q.getGroupBy().isBlank() ? "month" : q.getGroupBy();
        if (!"month".equals(groupBy) && !"day".equals(groupBy)) {
            throw new BizException("groupBy 必须是 month 或 day");
        }
        if ("day".equals(groupBy)) {
            if (q.getStart() == null || q.getEnd() == null) {
                throw new BizException("按日统计必须提供 start 和 end");
            }
            checkSpan(q.getStart(), q.getEnd(), ChronoUnit.DAYS, 366, "按日统计区间不能超过 366 天");
            return new Range(groupBy, q.getStart(), q.getEnd(),
                    q.getStart().atStartOfDay(), q.getEnd().plusDays(1).atStartOfDay());
        }
        if (q.getStart() != null && q.getEnd() != null) {
            checkSpan(q.getStart(), q.getEnd(), ChronoUnit.MONTHS, 24, "按月统计区间不能超过 24 个月");
            return new Range(groupBy, q.getStart(), q.getEnd(),
                    q.getStart().atStartOfDay(), q.getEnd().plusDays(1).atStartOfDay());
        }
        int months = q.getMonths() == null ? 6 : q.getMonths();
        if (months < 1 || months > 24) {
            throw new BizException("months 必须在 1~24 之间");
        }
        LocalDate now = LocalDate.now();
        LocalDate start = now.minusMonths(months - 1L).withDayOfMonth(1);
        return new Range(groupBy, start, now, start.atStartOfDay(), now.plusDays(1).atStartOfDay());
    }

    private void checkSpan(LocalDate start, LocalDate end, ChronoUnit unit, long max, String message) {
        if (start.isAfter(end)) {
            throw new BizException("start 不能晚于 end");
        }
        if (unit.between(start, end) > max) {
            throw new BizException(message);
        }
    }

    /** 桶标签全集（首尾齐全，无数据期也在内）：month → yyyy-MM；day → yyyy-MM-dd */
    private List<String> bucketLabels(Range r) {
        List<String> labels = new ArrayList<>();
        if ("month".equals(r.groupBy())) {
            YearMonth cursor = YearMonth.from(r.start());
            YearMonth last = YearMonth.from(r.end());
            while (!cursor.isAfter(last)) {
                labels.add(cursor.toString());
                cursor = cursor.plusMonths(1);
            }
        } else {
            for (LocalDate d = r.start(); !d.isAfter(r.end()); d = d.plusDays(1)) {
                labels.add(d.toString());
            }
        }
        return labels;
    }

    /** 桶的期末（含）：月桶 = 月末；日桶 = 当天。净资产累计「期初至今」用 */
    private LocalDate bucketEndDate(Range r, String label) {
        if ("month".equals(r.groupBy())) {
            return YearMonth.parse(label).atEndOfMonth();
        }
        return LocalDate.parse(label);
    }

    /**
     * 净资产趋势（UC-5.4 / 规格 5.4 规则 2、3）：
     * netWorth(期末) = Σ(asset.initial_balance) − Σ(liability.initial_balance) + Σ(期末前的 收入−支出)。
     * 含归档账户；转账双向抵消不进累计；区间起点前的流水计入（自时间起点累计，from 传 null）。
     */
    public StatsNetWorthVo netWorth(Long userId, StatsTrendQuery q) {
        Range range = resolveRange(q);
        List<Account> accounts = accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, userId));
        BigDecimal base = BigDecimal.ZERO;
        for (Account a : accounts) {
            BigDecimal initial = a.getInitialBalance() != null ? a.getInitialBalance() : BigDecimal.ZERO;
            base = base.add("liability".equals(a.getType()) ? initial.negate() : initial);
        }
        List<DailyFlowRow> flows = statsMapper.dailyFlows(userId, null, range.to(), null);

        StatsNetWorthVo vo = new StatsNetWorthVo();
        vo.setInitialNetWorth(base.setScale(2, RoundingMode.HALF_UP));
        List<NetWorthPointVo> points = new ArrayList<>();
        BigDecimal cum = base;
        int idx = 0; // flows 按 day 升序（SQL ORDER BY day），桶也按时间升序，双指针一遍累计
        for (String label : bucketLabels(range)) {
            LocalDate bucketEnd = bucketEndDate(range, label);
            while (idx < flows.size() && !flows.get(idx).getDay().isAfter(bucketEnd)) {
                DailyFlowRow f = flows.get(idx++);
                cum = cum.add("income".equals(f.getType()) ? f.getTotal() : f.getTotal().negate());
            }
            NetWorthPointVo p = new NetWorthPointVo();
            p.setLabel(label);
            p.setNetWorth(cum.setScale(2, RoundingMode.HALF_UP));
            points.add(p);
        }
        vo.setPoints(points);
        return vo;
    }

    /**
     * 分类构成（UC-5.5）：按一级分类聚合（子分类上卷，children 保留二级明细供下钻）；
     * category_id 为 NULL / 分类已删 / 父级缺失 的行一律归入「未分类」单列；items 按金额降序。
     */
    public StatsCategoryVo category(Long userId, StatsCategoryQuery q) {
        String type = q.getType() == null || q.getType().isBlank() ? "expense" : q.getType();
        if (!"expense".equals(type) && !"income".equals(type)) {
            throw new BizException("type 必须是 expense 或 income");
        }
        List<CategorySumRow> rows = statsMapper.sumByCategory(
                userId, type, fromOf(q.getStart()), toOf(q.getEnd()), q.getAccountId());

        List<Category> cats = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, type));
        Map<Long, Category> byId = cats.stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        Map<Long, CategoryStatItemVo> topById = new LinkedHashMap<>();
        CategoryStatItemVo uncategorized = null;
        BigDecimal total = BigDecimal.ZERO;
        for (CategorySumRow row : rows) {
            total = total.add(row.getTotal());
            Long topId = resolveTopId(row.getCategoryId(), byId);
            if (topId == null) {
                if (uncategorized == null) {
                    uncategorized = statItem(null, "未分类");
                }
                uncategorized.setAmount(uncategorized.getAmount().add(row.getTotal()));
            } else {
                CategoryStatItemVo top = topById.computeIfAbsent(topId,
                        id -> statItem(id, byId.get(id).getName()));
                top.setAmount(top.getAmount().add(row.getTotal()));
                if (!topId.equals(row.getCategoryId())) {
                    CategoryStatItemVo child = top.getChildren().stream()
                            .filter(c -> row.getCategoryId().equals(c.getCategoryId()))
                            .findFirst().orElse(null);
                    if (child == null) {
                        child = statItem(row.getCategoryId(), byId.get(row.getCategoryId()).getName());
                        top.getChildren().add(child);
                    }
                    child.setAmount(child.getAmount().add(row.getTotal()));
                }
            }
        }

        List<CategoryStatItemVo> items = new ArrayList<>(topById.values());
        if (uncategorized != null) {
            items.add(uncategorized);
        }
        items.sort((x, y) -> y.getAmount().compareTo(x.getAmount()));
        BigDecimal finalTotal = total;
        for (CategoryStatItemVo it : items) {
            it.setAmount(it.getAmount().setScale(2, RoundingMode.HALF_UP));
            it.setPercentage(percentage(it.getAmount(), finalTotal));
            it.getChildren().forEach(c -> {
                c.setAmount(c.getAmount().setScale(2, RoundingMode.HALF_UP));
                c.setPercentage(percentage(c.getAmount(), finalTotal));
            });
            it.getChildren().sort((x, y) -> y.getAmount().compareTo(x.getAmount()));
        }

        StatsCategoryVo vo = new StatsCategoryVo();
        vo.setType(type);
        vo.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        vo.setItems(items);
        return vo;
    }

    /** 子分类 → 一级分类；NULL / 已删 / 父级缺失 → null（归入未分类） */
    private Long resolveTopId(Long categoryId, Map<Long, Category> byId) {
        if (categoryId == null) {
            return null;
        }
        Category c = byId.get(categoryId);
        if (c == null) {
            return null;
        }
        if (c.getParentId() == null) {
            return categoryId;
        }
        return byId.containsKey(c.getParentId()) ? c.getParentId() : null;
    }

    private CategoryStatItemVo statItem(Long categoryId, String name) {
        CategoryStatItemVo vo = new CategoryStatItemVo();
        vo.setCategoryId(categoryId);
        vo.setName(name);
        vo.setAmount(BigDecimal.ZERO);
        vo.setPercentage(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        vo.setChildren(new ArrayList<>());
        return vo;
    }

    private BigDecimal percentage(BigDecimal amount, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }

    /** 解析后的统计区间：groupBy + 起止（含）+ SQL 边界（from 可空 = 无下界，to 排他） */
    private record Range(String groupBy, LocalDate start, LocalDate end,
                         LocalDateTime from, LocalDateTime to) {
    }
}
