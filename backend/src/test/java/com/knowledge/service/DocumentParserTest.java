package com.knowledge.service;

import com.knowledge.service.PdfParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** DocumentParser 单元测试 —— 测试文档解析和 Markdown 转换逻辑 */
@ExtendWith(MockitoExtension.class)
class DocumentParserTest {

    @Mock
    private PdfParser pdfParser;

    @InjectMocks
    private DocumentParser parser;

    @Test
    @DisplayName("不支持的格式应抛出异常")
    void parseUnsupportedFormat(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("test.txt");
        Files.writeString(file, "hello");

        assertThrows(IllegalArgumentException.class, () -> parser.parse(file));
    }

    @Test
    @DisplayName("toMarkdown — 空文本返回空字符串")
    void toMarkdownEmpty() {
        assertEquals("", parser.toMarkdown(""));
        assertEquals("", parser.toMarkdown("   \n  \n  "));
    }

    @Test
    @DisplayName("toMarkdown — 短行识别为标题")
    void toMarkdownDetectsHeadings() {
        String result = parser.toMarkdown("关于加强信息安全管理工作的通知");
        assertTrue(result.contains("###"), "短行应被识别为标题: " + result);
    }

    @Test
    @DisplayName("toMarkdown — 普通段落保持原样")
    void toMarkdownPreservesParagraphs() {
        String input = "这是一段比较长的文本内容，用于测试段落识别功能是否正常工作。";
        String result = parser.toMarkdown(input);
        assertTrue(result.contains("一段比较长的文本内容"));
        assertFalse(result.contains("###"), "长段落不应被识别为标题");
    }

    @Test
    @DisplayName("toMarkdown — 制表符转为 Markdown 表格")
    void toMarkdownConvertsTable() {
        String input = "姓名\t部门\t职务\n张三\t信息技术部\t工程师";
        String result = parser.toMarkdown(input);
        assertTrue(result.contains("|"), "应包含表格分隔符");
        assertTrue(result.contains("张三"), "表格内容应保留");
    }

    @Test
    @DisplayName("toMarkdown — 多段落处理")
    void toMarkdownMultipleParagraphs() {
        String input = """
                第一章 总则

                第一条 为了加强信息安全管理工作，保障信息系统安全稳定运行，
                根据国家有关法律法规，制定本规定。

                第二条 本规定适用于本单位所有部门。""";
        String result = parser.toMarkdown(input);
        assertTrue(result.contains("第一章"), "应保留标题");
        assertTrue(result.contains("第一条"), "应保留正文");
        assertTrue(result.contains("第二条"), "应保留多段落");
    }

    @Test
    @DisplayName("parse — OFD 文件返回占位文本（无真实 OFD 文件时）")
    void parseOfdPlaceholder(@TempDir Path tmpDir) throws IOException {
        // 创建一个无效的 OFD 文件（无法解析，验证兜底逻辑）
        Path file = tmpDir.resolve("test.ofd");
        Files.writeString(file, "not a real ofd");

        String result = parser.parse(file);
        assertNotNull(result);
        assertTrue(result.contains("OFD"), "OFD 解析失败应有友好提示");
    }

    @Test
    @DisplayName("parse — 文件名大小写不敏感，正确路由到对应解析器")
    void parseCaseInsensitive(@TempDir Path tmpDir) throws IOException {
        // DOCX 大小写测试
        Path fileUpper = tmpDir.resolve("TEST.DOCX");
        // 创建一个最小的有效 .docx（ZIP 格式）
        byte[] minimalDocx = new byte[] {
            0x50, 0x4B, 0x03, 0x04  // ZIP magic bytes
        };
        Files.write(fileUpper, minimalDocx);

        // 验证不是"不支持的文件格式"异常（说明进入了正确的解析分支）
        try {
            parser.parse(fileUpper);
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains("不支持的文件格式"),
                    "大小写判断应正确，不应报不支持格式");
        } catch (Exception ignored) {
            // 文件内容不完整导致解析失败是预期的
        }
    }
}
