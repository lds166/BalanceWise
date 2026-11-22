package com.balancewise.importbill;

import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.importbill.ai.AiClassifier;
import com.balancewise.importbill.ai.AiConfigService;
import com.balancewise.importbill.ai.AiProperties;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportPreviewVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 分类与预览编排的集成测试：用 @Primary 假分类器验证流水线接入
 * （未命中行被 AI 补齐并标 matchSource=ai；AI 挂掉时 notice 提示且导入不阻断）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportAiIntegrationTest {

    /** false=正常推荐；true=模拟 AI 故障（返回 error 结果） */
    static boolean failNext = false;

    @TestConfiguration
    static class FakeAiConfig {
        @Bean
        @Primary
        AiClassifier fakeAiClassifier(AiConfigService aiConfigService) {
            AiProperties p = new AiProperties();
            p.setEnabled(true);
            p.setApiKey("fake-key");
            return new AiClassifier(aiConfigService, p, new ObjectMapper(), RestClient.builder()) {
                @Override
                public AiOutcome classifyForUser(Long userId, List<Category> categories, List<ImportPreviewRow> rows) {
                    if (failNext) {
                        return new AiOutcome(Map.of(), "boom");
                    }
                    Map<Integer, Long> hits = new HashMap<>();
                    for (ImportPreviewRow r : rows) {
                        String kind = "expense".equals(r.getType()) ? "expense" : "income";
                        categories.stream().filter(c -> c.getKind().equals(kind)).findFirst()
                                .ifPresent(c -> hits.put(r.getLineNo(), c.getId()));
                    }
                    return new AiOutcome(hits, null);
                }
            };
        }
    }

    @Autowired
    private ImportService importService;
    @Autowired
    private CategoryMapper categoryMapper;

    @BeforeEach
    void reset() {
        failNext = false;
    }

    private Category category(String name, String kind) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return c;
    }

    private byte[] fixture() throws Exception {
        return Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
    }

    @Test
    void aiFillsUnclassifiedRowsAndMarksMatchSource() throws Exception {
        Category med = category("医疗", "expense");
        ImportPreviewVo vo = importService.preview(1L, "alipay", fixture(), null);

        // 行0/行1（支出）无记忆无规则命中 → AI 补齐并标记
        ImportPreviewRow row0 = vo.getRows().stream()
                .filter(r -> "202609010000100001".equals(r.getExternalId()))
                .findFirst().orElseThrow();
        assertEquals("ai", row0.getMatchSource());
        assertEquals(med.getId(), row0.getCategoryId());
        ImportPreviewRow row1 = vo.getRows().stream()
                .filter(r -> "202609050000100005".equals(r.getExternalId()))
                .findFirst().orElseThrow();
        assertEquals("ai", row1.getMatchSource());
        // 转账行不参与 AI 分类
        ImportPreviewRow transfer = vo.getRows().stream()
                .filter(r -> "transfer".equals(r.getType()))
                .findFirst().orElseThrow();
        assertNull(transfer.getMatchSource());
        // 无降级提示
        assertNull(vo.getNotice());
    }

    @Test
    void aiFailureShowsNoticeAndDoesNotBlockImport() throws Exception {
        category("医疗", "expense");
        failNext = true;
        ImportPreviewVo vo = importService.preview(1L, "alipay", fixture(), null);

        // 预览照常返回全部行（AI 故障不阻断）
        assertEquals(4, vo.getRows().size());
        assertTrue(vo.getRows().stream().noneMatch(r -> "ai".equals(r.getMatchSource())));
        assertNotNull(vo.getNotice());
        assertTrue(vo.getNotice().contains("AI 分类暂不可用"));
    }
}
