package com.balancewise.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.category.vo.CategoryVo;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryService extends ServiceImpl<CategoryMapper, Category> {

    public static final String[][] EXPENSE_DEFAULTS = {
            {"餐饮", "三餐", "外卖", "聚餐", "零食饮料"},
            {"交通", "公交地铁", "打车", "火车机票", "加油停车"},
            {"购物", "服饰鞋包", "数码家电", "日用百货", "美妆护肤"},
            {"居住", "房租", "水电燃气", "物业宽带"},
            {"娱乐", "电影游戏", "旅游度假", "运动健身", "会员充值"},
            {"医疗", "药品", "门诊住院"},
            {"教育", "书籍资料", "课程培训"},
            {"人情往来", "红包礼金", "请客送礼"},
            {"其他支出"},
    };

    public static final String[][] INCOME_DEFAULTS = {
            {"工资薪资"}, {"兼职副业"}, {"理财收益"}, {"红包礼金"}, {"其他收入"},
    };

    private final TransactionRecordMapper transactionRecordMapper;

    public CategoryService(TransactionRecordMapper transactionRecordMapper) {
        this.transactionRecordMapper = transactionRecordMapper;
    }

    @Transactional
    public void seedForUser(Long userId) {
        insertDefaults(userId, "expense", EXPENSE_DEFAULTS);
        insertDefaults(userId, "income", INCOME_DEFAULTS);
    }

    private void insertDefaults(Long userId, String kind, String[][] defaults) {
        for (String[] group : defaults) {
            Category parent = new Category();
            parent.setUserId(userId);
            parent.setParentId(null);
            parent.setName(group[0]);
            parent.setKind(kind);
            parent.setIsSystem(1);
            save(parent);
            for (int i = 1; i < group.length; i++) {
                Category child = new Category();
                child.setUserId(userId);
                child.setParentId(parent.getId());
                child.setName(group[i]);
                child.setKind(kind);
                child.setIsSystem(1);
                save(child);
            }
        }
    }

    public List<CategoryVo> tree(Long userId, String kind) {
        List<Category> all = list(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .orderByAsc(Category::getId));
        Map<Long, CategoryVo> byId = new LinkedHashMap<>();
        for (Category c : all) {
            CategoryVo vo = new CategoryVo();
            vo.setId(c.getId());
            vo.setName(c.getName());
            vo.setKind(c.getKind());
            vo.setIsSystem(c.getIsSystem());
            byId.put(c.getId(), vo);
        }
        List<CategoryVo> roots = new ArrayList<>();
        for (Category c : all) {
            CategoryVo vo = byId.get(c.getId());
            if (c.getParentId() == null) {
                roots.add(vo);
            } else {
                CategoryVo parent = byId.get(c.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                }
            }
        }
        return roots;
    }

    private void ensureNoDuplicateName(Long userId, String kind, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<Category> w = new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .eq(Category::getName, name)
                .eq(parentId != null, Category::getParentId, parentId)
                .isNull(parentId == null, Category::getParentId);
        if (excludeId != null) {
            w.ne(Category::getId, excludeId);
        }
        if (count(w) > 0) {
            throw new BizException("同级下已存在同名分类");
        }
    }

    private Category owned(Long userId, Long id) {
        Category c = getById(id);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BizException("分类不存在");
        }
        return c;
    }

    @Transactional
    public Category create(Long userId, CategoryRequest req) {
        Long parentId = req.getParentId();
        if (parentId != null) {
            Category parent = owned(userId, parentId);
            if (!parent.getKind().equals(req.getKind())) {
                throw new BizException("子分类与父分类类型不一致");
            }
            if (parent.getParentId() != null) {
                throw new BizException("只能在一级分类下新增子分类");
            }
        }
        ensureNoDuplicateName(userId, req.getKind(), parentId, req.getName(), null);
        Category c = new Category();
        c.setUserId(userId);
        c.setParentId(parentId);
        c.setName(req.getName());
        c.setKind(req.getKind());
        c.setIsSystem(0);
        save(c);
        return c;
    }

    @Transactional
    public void rename(Long userId, Long id, String newName) {
        Category c = owned(userId, id);
        ensureNoDuplicateName(userId, c.getKind(), c.getParentId(), newName, id);
        c.setName(newName);
        updateById(c);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Category c = owned(userId, id);
        if (Integer.valueOf(1).equals(c.getIsSystem())) {
            throw new BizException("预置分类不可删除");
        }
        long children = count(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getParentId, id));
        if (children > 0) {
            throw new BizException("请先删除该分类下的子分类");
        }
        long refs = transactionRecordMapper.selectCount(new LambdaQueryWrapper<TransactionRecord>()
                .eq(TransactionRecord::getUserId, userId)
                .eq(TransactionRecord::getCategoryId, id)
                .eq(TransactionRecord::getIsDeleted, 0));
        if (refs > 0) {
            throw new BizException("该分类已被交易使用，无法删除");
        }
        removeById(id);
    }
}
