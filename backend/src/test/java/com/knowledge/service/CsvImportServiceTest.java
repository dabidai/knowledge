package com.knowledge.service;

import com.knowledge.entity.Department;
import com.knowledge.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CsvImportService 单元测试 —— 测试 CSV 读取和编码检测。
 * 使用 @DataJpaTest 仅加载 JPA，GraphBuildService 用 Mockito mock。
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({CsvImportService.class, BCryptPasswordEncoder.class,
         CsvImportServiceTest.MockGraphConfig.class})
class CsvImportServiceTest {

    @TestConfiguration
    static class MockGraphConfig {
        @Bean
        GraphBuildService graphBuildService() {
            return mock(GraphBuildService.class);
        }
    }

    @Autowired private DepartmentRepository deptRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ItemRepository itemRepo;
    @Autowired private DocumentRepository docRepo;
    @Autowired private OpinionRepository opinionRepo;
    @Autowired private CsvImportService csvService;

    @BeforeEach
    void setUp() {
        deptRepo.save(Department.builder().name("测试部").build());
    }

    @Test
    @DisplayName("readCsv — UTF-8 编码 CSV 正确解析")
    void readCsvUtf8(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("test.csv");
        String csv = """
                col1,col2,col3
                a,b,c
                d,e,f
                """;
        Files.writeString(file, csv, StandardCharsets.UTF_8);

        List<String[]> rows = csvService.readCsv(file);
        assertEquals(3, rows.size(), "应含表头+2行数据");
        assertArrayEquals(new String[]{"col1", "col2", "col3"}, rows.get(0));
        assertArrayEquals(new String[]{"a", "b", "c"}, rows.get(1));
    }

    @Test
    @DisplayName("readCsv — GBK 编码 CSV 正确解析（中文内容）")
    void readCsvGbk(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("item.csv");
        String gbkContent = """
                事项ID,事项标题,事项分类,分类编号
                HZ001,关于信息安全工作的通知,通知,2021通1234
                """;
        Files.writeString(file, gbkContent, java.nio.charset.Charset.forName("GBK"));

        List<String[]> rows = csvService.readCsv(file);
        assertEquals(2, rows.size(), "应含表头+1行数据");
        assertTrue(rows.get(1)[0].startsWith("HZ"), "事项ID 应以 HZ 开头");
        assertTrue(rows.get(1)[1].contains("信息安全"), "标题应含中文关键词");
    }

    @Test
    @DisplayName("readCsv — 空文件返回空列表")
    void readCsvEmpty(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("empty.csv");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        List<String[]> rows = csvService.readCsv(file);
        assertEquals(0, rows.size());
    }

    @Test
    @DisplayName("importUserCsv — 正确创建部门和用户")
    void importUserCsv(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("user.csv");
        String csv = """
                type,dept_name,username,password,role
                user,测试部,testuser,test123,default
                """;
        Files.writeString(file, csv, StandardCharsets.UTF_8);

        var result = csvService.importUserCsv(file);
        assertTrue(result.get("users") >= 1, "应至少创建 1 个用户");
        assertTrue(userRepo.existsByUsername("testuser"), "用户应被创建");
    }

    @Test
    @DisplayName("importItemCsv — 正确导入事项元数据")
    void importItemCsv(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("item.csv");
        String csv = """
                事项ID,事项标题,事项发起时间,事项分类,分类编号,年度,字号,发文单位,事项类型
                ITEM001,测试事项标题,2024-01-01 08:00:00,通知,TEST2024通1,2024,通[1],测试单位,收文
                """;
        Files.writeString(file, csv, StandardCharsets.UTF_8);

        var items = csvService.importItemCsv(file, "公共区", true, "test-batch");
        assertEquals(1, items.size());
        assertEquals("测试事项标题", items.get(0).getTitle());
        assertEquals("通知", items.get(0).getCategory());
        assertEquals("TEST2024通1", items.get(0).getCategoryNo());
    }
}
