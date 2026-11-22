package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.importbill.entity.ImportAlias;
import com.balancewise.importbill.mapper.ImportAliasMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导入映射记忆（UC-4.10）：learn 在确认入账时把用户修正沉淀为个人映射；
 * matchMemory* 在预览时作为分类/账户匹配的第一层（优先于关键词规则与模糊匹配）。
 * 目标被删除/归属不符时静默视为未命中，绝不阻断导入。
 */
@Service
public class ImportAliasService {

    public static final String TYPE_CATEGORY = "category";
    public static final String TYPE_ACCOUNT = "account";
    /** 账户映射的 kind 占位（进唯一键） */
    public static final String KIND_ANY = "any";

    private static final int MAX_ALIAS_TEXT = 128;

    private final ImportAliasMapper aliasMapper;
    private final CategoryMapper categoryMapper;
    private final AccountMapper accountMapper;

    public ImportAliasService(ImportAliasMapper aliasMapper,
                              CategoryMapper categoryMapper,
                              AccountMapper accountMapper) {
        this.aliasMapper = aliasMapper;
        this.categoryMapper = categoryMapper;
        this.accountMapper = accountMapper;
    }

    /** 沉淀分类记忆：aliasText 取对方（为空取商品名）；分类须归属当前用户且 kind 一致才写入 */
    public void learnCategory(Long userId, String kind, String counterparty, String item, Long categoryId) {
        String text = firstNonBlank(counterparty, item);
        if (text == null || categoryId == null) {
            return;
        }
        Category cat = categoryMapper.selectById(categoryId);
        if (cat == null || !cat.getUserId().equals(userId) || !cat.getKind().equals(kind)) {
            return; // 目标无效静默跳过，学习绝不阻断入账
        }
        upsert(userId, TYPE_CATEGORY, kind, text, cat.getId(), cat.getName());
    }

    /** 沉淀账户记忆：payMethod 原文 → 账户；账户须归属当前用户且未归档才写入 */
    public void learnAccount(Long userId, String payMethod, Long accountId) {
        if (payMethod == null || payMethod.isBlank() || accountId == null) {
            return;
        }
        Account acc = accountMapper.selectById(accountId);
        if (acc == null || !acc.getUserId().equals(userId) || Integer.valueOf(1).equals(acc.getIsArchived())) {
            return;
        }
        upsert(userId, TYPE_ACCOUNT, KIND_ANY, payMethod, acc.getId(), acc.getName());
    }

    /** 分类记忆命中：kind 必须一致（支出/收入记忆分开存，退款行不会被支出记忆污染）；未命中返回 null */
    public Long matchMemoryCategory(Long userId, String kind, String counterparty, String item) {
        String text = firstNonBlank(counterparty, item);
        if (text == null) {
            return null;
        }
        ImportAlias alias = selectAlias(userId, TYPE_CATEGORY, kind, text);
        if (alias == null) {
            return null;
        }
        Category cat = categoryMapper.selectById(alias.getTargetId());
        if (cat == null || !cat.getUserId().equals(userId) || !cat.getKind().equals(kind)) {
            return null; // 记忆失效（分类被删/改）静默回落到下一层
        }
        return cat.getId();
    }

    /** 账户记忆命中：返回目标账户 id（归属与归档校验由调用方完成）；未命中返回 null */
    public Long matchMemoryAccount(Long userId, String payMethod) {
        if (payMethod == null || payMethod.isBlank()) {
            return null;
        }
        ImportAlias alias = selectAlias(userId, TYPE_ACCOUNT, KIND_ANY, payMethod);
        return alias == null ? null : alias.getTargetId();
    }

    /** 记忆管理列表（按更新时间倒序） */
    public List<ImportAlias> list(Long userId) {
        return aliasMapper.selectList(new LambdaQueryWrapper<ImportAlias>()
                .eq(ImportAlias::getUserId, userId)
                .orderByDesc(ImportAlias::getUpdatedAt));
    }

    /** 删除记忆（归属校验后物理删除） */
    public void delete(Long userId, Long id) {
        ImportAlias alias = aliasMapper.selectById(id);
        if (alias == null || !alias.getUserId().equals(userId)) {
            throw new BizException("记忆不存在");
        }
        aliasMapper.deleteById(id);
    }

    private ImportAlias selectAlias(Long userId, String type, String kind, String text) {
        return aliasMapper.selectOne(new LambdaQueryWrapper<ImportAlias>()
                .eq(ImportAlias::getUserId, userId)
                .eq(ImportAlias::getAliasType, type)
                .eq(ImportAlias::getKind, kind)
                .eq(ImportAlias::getAliasText, truncate(text))
                .last("LIMIT 1"));
    }

    private void upsert(Long userId, String type, String kind, String text, Long targetId, String targetName) {
        ImportAlias existing = selectAlias(userId, type, kind, text);
        if (existing != null) {
            // 同键再学习视为再次确认：刷新目标（last-write-wins）并累计命中
            existing.setTargetId(targetId);
            existing.setTargetName(targetName);
            existing.setHitCount(existing.getHitCount() == null ? 1 : existing.getHitCount() + 1);
            aliasMapper.updateById(existing);
            return;
        }
        ImportAlias alias = new ImportAlias();
        alias.setUserId(userId);
        alias.setAliasType(type);
        alias.setKind(kind);
        alias.setAliasText(truncate(text));
        alias.setTargetId(targetId);
        alias.setTargetName(targetName);
        alias.setHitCount(0);
        aliasMapper.insert(alias);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private String truncate(String text) {
        String t = text.trim();
        return t.length() <= MAX_ALIAS_TEXT ? t : t.substring(0, MAX_ALIAS_TEXT);
    }
}
