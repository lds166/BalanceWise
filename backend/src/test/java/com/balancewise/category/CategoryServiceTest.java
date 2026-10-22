package com.balancewise.category;

import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.entity.Category;
import com.balancewise.category.service.CategoryService;
import com.balancewise.category.vo.CategoryVo;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CategoryServiceTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    @Test
    void seedCreatesDefaultsForNewUser() {
        categoryService.seedForUser(100L);
        List<CategoryVo> expense = categoryService.tree(100L, "expense");
        List<CategoryVo> income = categoryService.tree(100L, "income");
        assertEquals(9, expense.size());
        assertEquals(5, income.size());
        assertEquals(4, expense.get(0).getChildren().size()); // 餐饮：三餐/外卖/聚餐/零食饮料
        assertEquals(Integer.valueOf(1), expense.get(0).getIsSystem());
    }

    @Test
    void createRejectsDuplicateSiblingName() {
        categoryService.seedForUser(101L);
        List<CategoryVo> tree = categoryService.tree(101L, "expense");
        CategoryVo dining = tree.get(0); // 餐饮
        CategoryRequest req = new CategoryRequest();
        req.setKind("expense");
        req.setName("外卖");
        req.setParentId(dining.getId());
        BizException e = assertThrows(BizException.class, () -> categoryService.create(101L, req));
        assertEquals("同级下已存在同名分类", e.getMessage());
    }

    @Test
    void systemCategoryCannotBeDeleted() {
        categoryService.seedForUser(102L);
        CategoryVo first = categoryService.tree(102L, "expense").get(0);
        BizException e = assertThrows(BizException.class, () -> categoryService.delete(102L, first.getId()));
        assertEquals("预置分类不可删除", e.getMessage());
    }

    @Test
    void categoryReferencedByTransactionCannotBeDeleted() {
        categoryService.seedForUser(103L);
        CategoryRequest req = new CategoryRequest();
        req.setKind("expense");
        req.setName("临时分类");
        Category custom = categoryService.create(103L, req);

        TransactionRecord tx = new TransactionRecord();
        tx.setUserId(103L);
        tx.setType("expense");
        tx.setAmount(new BigDecimal("10.00"));
        tx.setCategoryId(custom.getId());
        tx.setOccurredAt(LocalDateTime.now());
        tx.setSource("manual");
        tx.setIsDeleted(0);
        transactionRecordMapper.insert(tx);

        BizException e = assertThrows(BizException.class, () -> categoryService.delete(103L, custom.getId()));
        assertEquals("该分类已被交易使用，无法删除", e.getMessage());
    }

    @Test
    void renameWorksAndDuplicateRejected() {
        categoryService.seedForUser(104L);
        CategoryVo first = categoryService.tree(104L, "income").get(0); // 工资薪资
        categoryService.rename(104L, first.getId(), "主业工资");
        assertEquals("主业工资", categoryService.tree(104L, "income").get(0).getName());
        CategoryVo second = categoryService.tree(104L, "income").get(1);
        BizException e = assertThrows(BizException.class,
                () -> categoryService.rename(104L, second.getId(), "主业工资"));
        assertEquals("同级下已存在同名分类", e.getMessage());
    }
}
