package com.balancewise.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.account.entity.Account;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface AccountMapper extends BaseMapper<Account> {

    /**
     * 余额联动唯一入口：原子增减 balance，禁止业务代码读改写余额。
     * 负债账户余额为负（规格 5.4 规则 2），任何流水 = from 减 / to 加。
     * NOW() 在 MySQL 8 与 H2(MySQL 模式) 均可用。
     */
    @Update("UPDATE account SET balance = balance + #{delta}, updated_at = NOW() WHERE id = #{id}")
    int addBalance(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
