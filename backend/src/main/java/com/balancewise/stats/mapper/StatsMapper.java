package com.balancewise.stats.mapper;

import com.balancewise.stats.mapper.row.DailyFlowRow;
import com.balancewise.stats.mapper.row.TypeSumRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计聚合查询（UC-5.x）：全部只读。
 * 只统计 is_deleted=0 且 type IN ('expense','income') 的流水（转账双向抵消，不进收支）。
 * 时间边界与 P2 buildWrapper 一致：from 含（>=），to 排他（< 次日 00:00:00）。
 * DATE()/SUM()/GROUP BY 语法同时兼容 MySQL 8 与 H2 MySQL 模式。
 */
public interface StatsMapper {

    /** 区间内按类型汇总；from 可空（无下界），accountId 可空（命中 from 或 to 任一账户） */
    @Select("<script>" +
            "SELECT type, SUM(amount) AS total FROM transaction_record " +
            "WHERE user_id = #{userId} AND is_deleted = 0 AND type IN ('expense','income') " +
            "<if test='from != null'>AND occurred_at &gt;= #{from} </if>" +
            "AND occurred_at &lt; #{to} " +
            "<if test='accountId != null'>AND (from_account_id = #{accountId} OR to_account_id = #{accountId}) </if>" +
            "GROUP BY type" +
            "</script>")
    List<TypeSumRow> sumByType(@Param("userId") Long userId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("accountId") Long accountId);

    /** 区间内按日+类型汇总，按 day 升序返回；from 可空（无下界） */
    @Select("<script>" +
            "SELECT DATE(occurred_at) AS `day`, type, SUM(amount) AS total FROM transaction_record " +
            "WHERE user_id = #{userId} AND is_deleted = 0 AND type IN ('expense','income') " +
            "<if test='from != null'>AND occurred_at &gt;= #{from} </if>" +
            "AND occurred_at &lt; #{to} " +
            "<if test='accountId != null'>AND (from_account_id = #{accountId} OR to_account_id = #{accountId}) </if>" +
            "GROUP BY DATE(occurred_at), type ORDER BY `day`" +
            "</script>")
    List<DailyFlowRow> dailyFlows(@Param("userId") Long userId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  @Param("accountId") Long accountId);
}
