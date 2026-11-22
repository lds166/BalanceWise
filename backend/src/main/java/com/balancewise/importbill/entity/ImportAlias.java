package com.balancewise.importbill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入映射记忆（UC-4.10）：用户在预览中修正分类/账户并勾选"记住"后沉淀的个人映射，
 * 下次导入优先于内置关键词规则命中。
 */
@Data
@TableName("import_alias")
public class ImportAlias {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** category 分类映射 / account 付款方式→账户 */
    private String aliasType;
    /** 分类映射存 expense/income；账户映射统一 any（进唯一键，使同商户支出/收入记忆可共存） */
    private String kind;
    /** 记忆文本：分类=对方（为空取商品名）；账户=付款方式原文 */
    private String aliasText;
    /** 目标 id：category.id 或 account.id */
    private Long targetId;
    /** 目标名称快照（记忆管理列表展示用） */
    private String targetName;
    private Integer hitCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
