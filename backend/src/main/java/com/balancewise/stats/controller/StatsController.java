package com.balancewise.stats.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.stats.dto.StatsCategoryQuery;
import com.balancewise.stats.dto.StatsTrendQuery;
import com.balancewise.stats.service.StatsService;
import com.balancewise.stats.vo.StatsCategoryVo;
import com.balancewise.stats.vo.StatsNetWorthVo;
import com.balancewise.stats.vo.StatsSummaryVo;
import com.balancewise.stats.vo.StatsTrendVo;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 统计报表只读端点（UC-5.x，规格 §6 统计节）。全部只读，不触碰任何账务写路径。 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 汇总卡片（UC-5.2）：start/end 可选（yyyy-MM-dd，含当天），accountId 可选 */
    @GetMapping("/summary")
    public Result<StatsSummaryVo> summary(@CurrentUser Long userId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                          @RequestParam(required = false) Long accountId) {
        return Result.ok(statsService.summary(userId, start, end, accountId));
    }

    /** 收支趋势（UC-5.3）：groupBy=month（months 近 N 个月或 start~end）/ day（必须 start+end） */
    @GetMapping("/trend")
    public Result<StatsTrendVo> trend(@CurrentUser Long userId, StatsTrendQuery q) {
        return Result.ok(statsService.trend(userId, q));
    }

    /** 净资产趋势（UC-5.4）：参数同 trend（accountId 忽略：净资产是全账户口径） */
    @GetMapping("/net-worth")
    public Result<StatsNetWorthVo> netWorth(@CurrentUser Long userId, StatsTrendQuery q) {
        return Result.ok(statsService.netWorth(userId, q));
    }

    /** 分类构成（UC-5.5）：type=expense/income，按一级分类聚合，children 二级明细供下钻 */
    @GetMapping("/category")
    public Result<StatsCategoryVo> category(@CurrentUser Long userId, StatsCategoryQuery q) {
        return Result.ok(statsService.category(userId, q));
    }
}
