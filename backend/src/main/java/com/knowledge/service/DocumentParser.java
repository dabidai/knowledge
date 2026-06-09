package com.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文档解析引擎
 * 支持 doc / docx / pdf（OFD 后续接入）
 */
@Slf4j
@Component
public class DocumentParser {

    /** 解析文档为纯文本 */
    public String parse(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".doc")) {
            return parseDoc(filePath);
        } else if (fileName.endsWith(".docx")) {
            return parseDocx(filePath);
        } else if (fileName.endsWith(".pdf")) {
            return parsePdf(filePath);
        } else if (fileName.endsWith(".ofd")) {
            return parseOfd(filePath);
        } else {
            throw new IllegalArgumentException("不支持的文件格式: " + fileName);
        }
    }

    /** 解析 .doc (旧格式) */
    private String parseDoc(Path filePath) throws IOException {
        try (InputStream is = java.nio.file.Files.newInputStream(filePath);
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /** 解析 .docx */
    private String parseDocx(Path filePath) throws IOException {
        try (InputStream is = java.nio.file.Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            // 提取表格内容
            doc.getTables().forEach(table -> {
                sb.append("\n[表格]\n");
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell ->
                            sb.append(cell.getText()).append("\t"));
                    sb.append("\n");
                });
            });
            return sb.toString();
        }
    }

    /** 解析 PDF */
    private String parsePdf(Path filePath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /** 解析 OFD（占位，后续接入 ofdrw） */
    private String parseOfd(Path filePath) {
        log.warn("OFD 解析尚未实现: {}", filePath);
        return "[OFD 文档解析待实现] " + filePath.getFileName();
    }

    /**
     * 将文本转换为结构化 Markdown
     * 根据段落特征添加标题标记和表格格式
     */
    public String toMarkdown(String plainText) {
        // 基础转换：保留段落结构，规范化换行
        StringBuilder md = new StringBuilder();
        String[] lines = plainText.split("\n");
        boolean inTable = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (inTable) { md.append("\n"); inTable = false; }
                md.append("\n");
                continue;
            }

            // 检测表格行（含多个制表符）
            if (trimmed.contains("\t")) {
                if (!inTable) { md.append("\n"); inTable = true; }
                md.append("| ").append(trimmed.replace("\t", " | ")).append(" |\n");
                continue;
            }

            // 检测标题（短行 + 无标点结尾，可能是标题）
            if (trimmed.length() <= 50 && !trimmed.endsWith("。")
                    && !trimmed.endsWith("，") && !trimmed.endsWith("；")) {
                md.append("### ").append(trimmed).append("\n\n");
                continue;
            }

            md.append(trimmed).append("\n\n");
        }

        return md.toString().trim();
    }
}
