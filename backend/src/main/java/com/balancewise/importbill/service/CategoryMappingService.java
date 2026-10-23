package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类自动映射（UC-4.4）：内置"平台交易分类 → 本系统分类"规则。
 * 先按平台分类精确表查，再按关键词规则匹配 平台分类+商品 文本；
 * 只在用户自己的分类中按名称解析；无匹配返回 null（未分类）。
 */
@Service
public class CategoryMappingService {

    /** 关键词规则：关键词 → 系统分类名（按顺序优先匹配） */
    private static final String[][] KEYWORD_RULES = {
            {"外卖", "餐饮"}, {"餐", "餐饮"}, {"美食", "餐饮"},
            {"超市", "购物"}, {"便利", "购物"}, {"百货", "购物"}, {"淘宝", "购物"}, {"天猫", "购物"}, {"京东", "购物"},
            {"打车", "交通"}, {"加油", "交通"}, {"地铁", "交通"}, {"公交", "交通"}, {"高铁", "交通"}, {"火车", "交通"},
            {"电影", "娱乐"}, {"会员", "娱乐"}, {"音乐", "娱乐"}, {"游戏", "娱乐"},
            {"房租", "居住"}, {"水电", "居住"}, {"物业", "居住"},
            {"工资", "工资"}, {"奖金", "工资"}, {"报销", "其他收入"},
    };

    private final CategoryMapper categoryMapper;

    public CategoryMappingService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public Long mapCategoryId(Long userId, String type, String platformCategory, String item) {
        if ("transfer".equals(type)) {
            return null; // 转账不挂分类
        }
        String kind = "expense".equals(type) ? "expense" : "income";
        String text = ((platformCategory == null ? "" : platformCategory) + " "
                + (item == null ? "" : item)).toLowerCase();
        for (String[] rule : KEYWORD_RULES) {
            if (text.contains(rule[0])) {
                Long id = resolveByName(userId, kind, rule[1]);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    private Long resolveByName(Long userId, String kind, String name) {
        List<Category> list = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .eq(Category::getName, name));
        return list.isEmpty() ? null : list.get(0).getId();
    }
}
