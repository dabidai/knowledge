package com.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;

import javax.xml.XMLConstants;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 文档解析引擎
 * 支持 doc / docx / pdf（OFD 后续接入）
 */
@Slf4j
@Component
public class DocumentParser {

    static {
        // 放宽 ZIP bomb 检测 —— 有些合法 docx/ofd 内嵌压缩率极高的 emf/wmf 图片
        ZipSecureFile.setMinInflateRatio(0.001);
    }

    /** 解析文档为纯文本 */
    public String parse(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        try {
            if (fileName.endsWith(".doc")) {
                return parseDocOrDocx(filePath, false);
            } else if (fileName.endsWith(".docx")) {
                return parseDocOrDocx(filePath, true);
            } else if (fileName.endsWith(".pdf")) {
                return parsePdf(filePath);
            } else if (fileName.endsWith(".ofd")) {
                return parseOfd(filePath);
            } else if (fileName.endsWith(".wps")) {
                return parseWps(filePath);
            } else if (fileName.endsWith(".txt")) {
                return parseTxt(filePath);
            } else {
                throw new IllegalArgumentException("不支持的文件格式: " + fileName);
            }
        } catch (Exception e) {
            throw new IOException("解析失败: " + filePath.getFileName(), e);
        }
    }

    /**
     * 解析 .doc 或 .docx —— 兼容格式与实际内容不一致的情况。
     * 实际中很多 .doc 文件其实是 OOXML，反之亦然。
     */
    private String parseDocOrDocx(Path filePath, boolean preferDocx) throws IOException {
        try {
            if (preferDocx) {
                return tryParseWithOOXML(filePath);
            } else {
                return tryParseWithOLE2(filePath);
            }
        } catch (Exception e) {
            log.debug("首选格式解析失败，尝试另一种格式: {}", filePath.getFileName());
            try {
                if (preferDocx) {
                    return tryParseWithOLE2(filePath);
                } else {
                    return tryParseWithOOXML(filePath);
                }
            } catch (Exception e2) {
                log.error("文档解析失败: {} ({})", filePath.getFileName(), e2.getMessage());
                return "[文档解析失败 — " + e2.getMessage() + "] " + filePath.getFileName();
            }
        }
    }

    /** 按 OOXML (docx) 格式解析 */
    private String tryParseWithOOXML(Path filePath) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(pkg)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
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

    /** 按 OLE2 (.doc) 格式解析 */
    private String tryParseWithOLE2(Path filePath) throws IOException {
        try (InputStream is = java.nio.file.Files.newInputStream(filePath);
             POIFSFileSystem fs = new POIFSFileSystem(is);
             HWPFDocument doc = new HWPFDocument(fs);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
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

    /** 创建安全的 DocumentBuilderFactory（禁用 XXE） */
    private static DocumentBuilderFactory secureFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            throw new RuntimeException("无法初始化安全的 XML 解析器", e);
        }
        return factory;
    }

    /**
     * 解析 OFD 版式文档
     * OFD 本质是 ZIP 压缩包，内含 XML 文件。遍历 Content.xml 提取文本。
     */
    private String parseOfd(Path filePath) {
        try (ZipFile zip = new ZipFile(filePath.toFile())) {
            DocumentBuilderFactory factory = secureFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            StringBuilder sb = new StringBuilder();

            // 1. 先读取 OFD.xml 获取文档入口结构
            // 2. 遍历所有 Content_*.xml（即各页面的内容文件）
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // OFD 页面内容文件通常在 Doc_0/Pages/Content_*.xml 或 Pages/Content_*.xml
                if (name.contains("Content") && name.endsWith(".xml")
                        && !name.contains("_Res") && !name.contains("Annotations")) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        Document doc = builder.parse(is);
                        extractTextFromXml(doc.getDocumentElement(), sb);
                    }
                }
            }

            // 如果没有找到 Content 文件，尝试读取所有 XML 文件
            if (sb.isEmpty()) {
                entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".xml") && !entry.getName().equals("OFD.xml")) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            Document doc = builder.parse(is);
                            extractTextFromXml(doc.getDocumentElement(), sb);
                        }
                    }
                }
            }

            // 去重和清理
            String result = sb.toString().trim();
            if (result.isEmpty()) {
                log.warn("OFD 文件未能提取到文本: {}", filePath.getFileName());
                return "[OFD 文档 — 未能提取文本内容] " + filePath.getFileName();
            }
            return result;
        } catch (Exception e) {
            log.error("OFD 解析失败: {}", filePath, e);
            return "[OFD 文档 — 解析失败] " + filePath.getFileName();
        }
    }

    /**
     * 解析 WPS 文字文档
     * 新版 WPS (.wps) 基于 OOXML（即 XML 的 ZIP 压缩包），结构与 DOCX 类似。
     * 尝试：1. 按 DOCX 方式读取  2. 按 ZIP+XML 提取文本
     */
    private String parseWps(Path filePath) throws IOException {
        // 先尝试以 DOCX 方式打开（新版 WPS 兼容 OOXML）
        try (InputStream is = java.nio.file.Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            doc.getTables().forEach(table -> {
                sb.append("\n[表格]\n");
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell ->
                            sb.append(cell.getText()).append("\t"));
                    sb.append("\n");
                });
            });
            if (!sb.isEmpty()) return sb.toString();
        } catch (Exception ignored) {
            // 不是 OOXML 格式，尝试 ZIP+XML 方式
        }

        // 回退：当作 ZIP 压缩包，提取所有 XML 中的文本
        try (ZipFile zip = new ZipFile(filePath.toFile())) {
            DocumentBuilderFactory factory = secureFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            StringBuilder sb = new StringBuilder();

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".xml") && !entry.isDirectory()) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        Document doc = builder.parse(is);
                        extractTextFromXml(doc.getDocumentElement(), sb);
                    }
                }
            }

            if (!sb.isEmpty()) return sb.toString().trim();
            log.warn("WPS 文件未能提取到文本: {}", filePath.getFileName());
            return "[WPS 文档 — 未能提取文本内容] " + filePath.getFileName();
        } catch (Exception e) {
            log.error("WPS 解析失败: {}", filePath, e);
            return "[WPS 文档 — 解析失败] " + filePath.getFileName();
        }
    }

    /** 解析 .txt 纯文本文件 */
    private String parseTxt(Path filePath) throws IOException {
        return java.nio.file.Files.readString(filePath);
    }

    /** 递归提取 XML 节点中的文本 */
    private void extractTextFromXml(Node node, StringBuilder sb) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getNodeValue().trim();
            if (!text.isEmpty()) {
                sb.append(text).append("\n");
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            extractTextFromXml(children.item(i), sb);
        }
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
