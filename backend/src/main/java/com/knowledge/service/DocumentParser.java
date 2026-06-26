package com.knowledge.service;

import com.knowledge.service.PdfParser.PdfParseResult;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.DocumentNode;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 文档解析引擎
 * 支持 doc / docx / pdf（OCR 增强）/ ofd / wps / txt
 */
@Slf4j
@Component
public class DocumentParser {

    private final PdfParser pdfParser;
    private final Tesseract tesseract;

    public DocumentParser(PdfParser pdfParser, Tesseract tesseract) {
        this.pdfParser = pdfParser;
        this.tesseract = tesseract;
    }

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

    /** 解析 PDF 并返回质量元数据（供 ImportService 写入 Document 实体） */
    public PdfParseResult parsePdfWithMeta(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".pdf")) {
            throw new IllegalArgumentException("非 PDF 文件: " + fileName);
        }
        return pdfParser.parse(filePath);
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
            String text = sb.toString();
            String ocrText = ocrImagesFromZip(filePath);
            if (!ocrText.isEmpty()) text += "\n" + ocrText;
            return text;
        }
    }

    /** 按 OLE2 (.doc) 格式解析 */
    private String tryParseWithOLE2(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath);
             POIFSFileSystem fs = new POIFSFileSystem(is);
             HWPFDocument doc = new HWPFDocument(fs);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = extractor.getText();
            String ocrText = ocrImagesFromDoc(filePath);
            if (!ocrText.isEmpty()) text += "\n" + ocrText;
            return text;
        }
    }

    /** 解析 PDF —— 委托 PdfParser 双轨处理（原生文字 + OCR 降级） */
    private String parsePdf(Path filePath) throws IOException {
        return pdfParser.parse(filePath).text();
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
                result = "[OFD 文档 — 未能提取文本内容] " + filePath.getFileName();
            }
            String ocrText = ocrImagesFromZip(filePath);
            if (!ocrText.isEmpty()) result += "\n" + ocrText;
            return result;
        } catch (Exception e) {
            log.error("OFD 解析失败: {}", filePath, e);
            return "[OFD 文档 — 解析失败] " + filePath.getFileName();
        }
    }

    /**
     * 解析 WPS 文字文档
     * 尝试：1. OOXML（新版 WPS） 2. OLE2（老版 WPS，Composite Document File） 3. ZIP+XML
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
            if (!sb.isEmpty()) {
                String ocrText = ocrImagesFromZip(filePath);
                if (!ocrText.isEmpty()) sb.append("\n").append(ocrText);
                return sb.toString();
            }
        } catch (Exception ignored) {
            // 不是 OOXML 格式
        }

        // 回退 1：按 OLE2 二进制格式读取（老版 WPS 与 .doc 同格式）
        try (InputStream is = Files.newInputStream(filePath);
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = extractor.getText();
            if (text != null && !text.isBlank()) {
                try {
                    String ocrText = ocrImagesFromDoc(filePath);
                    if (!ocrText.isEmpty()) text += "\n" + ocrText;
                } catch (Exception e) {
                    log.debug("WPS 图片 OCR 忽略: {}", e.getMessage());
                }
                return text;
            }
        } catch (Exception ignored) {
            // HWPFDocument 失败，尝试从 OLE2 文件系统直接提取文本
            String rawText = extractRawTextFromOle2(filePath);
            if (rawText != null) return rawText;
        }

        // 回退 2：当作 ZIP 压缩包，提取所有 XML 中的文本
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

            if (!sb.isEmpty()) {
                String result = sb.toString().trim();
                String ocrText = ocrImagesFromZip(filePath);
                if (!ocrText.isEmpty()) result += "\n" + ocrText;
                return result;
            }
            log.warn("WPS 文件未能提取到文本: {}", filePath.getFileName());
            return "[WPS 文档 — 未能提取文本内容] " + filePath.getFileName();
        } catch (Exception e) {
            log.error("WPS 解析失败: {}", filePath, e);
            return "[WPS 文档 — 解析失败] " + filePath.getFileName();
        }
    }

    /** 解析 .txt 纯文本文件 */
    private String parseTxt(Path filePath) throws IOException {
        return Files.readString(filePath);
    }

    /**
     * 从 OLE2 文件系统扫描 Unicode 文本段（当 HWPFDocument 无法处理时回退）
     * Word OLE2 流使用 UTF-16LE 编码，二进制格式数据中嵌入的文本段可通过
     * 逐字节扫描有效 Unicode 字符范围来恢复。
     */
    private static String extractRawTextFromOle2(Path filePath) {
        List<byte[]> allData = new ArrayList<>();
        try (InputStream is = Files.newInputStream(filePath);
             POIFSFileSystem fs = new POIFSFileSystem(is)) {
            collectOle2Streams(fs.getRoot(), allData);
        } catch (Exception e) {
            log.debug("OLE2 文件系统打开失败: {}", e.getMessage());
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (byte[] data : allData) {
            scanUnicodeText(data, result);
        }
        String text = result.toString().trim();
        return text.length() > 20 ? text : null;
    }

    /** 收集 OLE2 中所有非元数据的文档流字节数据 */
    private static void collectOle2Streams(DirectoryNode dir, List<byte[]> out) {
        for (org.apache.poi.poifs.filesystem.Entry entry : dir) {
            if (entry.isDirectoryEntry()) {
                collectOle2Streams((DirectoryNode) entry, out);
            } else if (entry.isDocumentEntry()) {
                String name = entry.getName().toLowerCase();
                if (name.contains("summaryinformation")
                        || name.contains("objectpool")
                        || name.equals("compobj")
                        || name.endsWith(".bin")) continue;
                try (DocumentInputStream dis = new DocumentInputStream((DocumentNode) entry)) {
                    byte[] data = dis.readAllBytes();
                    if (data.length >= 20 && data.length <= 10 * 1024 * 1024) {
                        out.add(data);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 以 UTF-16LE 逐字节扫描可读文本段
     * Word 文档流使用 UTF-16LE 编码，但混杂二进制格式头。
     * 有效 Unicode 范围：ASCII 可打印、CJK 统一表意文字、常见标点
     */
    private static void scanUnicodeText(byte[] data, StringBuilder out) {
        StringBuilder sb = new StringBuilder();
        boolean prevValid = false;
        int maxLen = data.length & ~1;  // 对齐到偶数
        for (int i = 0; i < maxLen; i += 2) {
            int lo = data[i] & 0xff;
            int hi = data[i + 1] & 0xff;
            char c = (char) ((hi << 8) | lo);

            boolean valid;
            if (c >= 0x20 && c <= 0x7e) valid = true;               // ASCII 可打印
            else if (c >= 0x4E00 && c <= 0x9FFF) valid = true;      // CJK 统一表意文字
            else if (c >= 0x3000 && c <= 0x303F) valid = true;      // CJK 符号
            else if (c >= 0xFF00 && c <= 0xFFEF) valid = true;      // 全角形式
            else if (c == '\n' || c == '\r' || c == '\t') valid = true;
            else if (c == 0x3000) valid = true;                     // 全角空格
            else valid = false;

            if (valid) {
                if (!prevValid && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                sb.append(c);
                prevValid = true;
            } else {
                prevValid = false;
            }
        }

        String text = sb.toString().replaceAll("\\s+", " ").trim();
        // 至少 30 个有效字符才认为是有效文本段
        if (text.length() > 30) {
            out.append(text).append("\n");
        }
    }

    /** 从 ZIP 格式文档中提取内嵌图片并 OCR（适用于 DOCX/WPS/OFD） */
    private String ocrImagesFromZip(Path filePath) {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(filePath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!isImageFile(entry.getName())) continue;
                if (entry.getSize() > 50 * 1024 * 1024) {
                    log.debug("图片过大，跳过 OCR: {} ({}MB)", entry.getName(), entry.getSize() / 1024 / 1024);
                    continue;
                }
                try (InputStream is = zip.getInputStream(entry)) {
                    BufferedImage image = ImageIO.read(is);
                    if (image != null) {
                        try {
                            String text = tesseract.doOCR(image);
                            if (text != null && !text.isBlank()) {
                                sb.append("\n[图片文字]\n").append(text.trim()).append("\n");
                            }
                        } finally {
                            image.flush();
                        }
                    }
                } catch (Exception e) {
                    log.debug("图片 OCR 失败: {}, {}", entry.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("提取内嵌图片失败: {}, {}", filePath, e.getMessage());
        }
        return sb.toString();
    }

    /** 从 OLE2 (.doc) 格式文档中提取内嵌图片并 OCR */
    private String ocrImagesFromDoc(Path filePath) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = Files.newInputStream(filePath);
             POIFSFileSystem fs = new POIFSFileSystem(is);
             HWPFDocument doc = new HWPFDocument(fs)) {
            PicturesTable pictures = doc.getPicturesTable();
            if (pictures != null) {
                for (Picture pic : pictures.getAllPictures()) {
                    try {
                        byte[] content = pic.getContent();
                        if (content.length > 50 * 1024 * 1024) continue;
                        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
                        if (image != null) {
                            try {
                                String text = tesseract.doOCR(image);
                                if (text != null && !text.isBlank()) {
                                    sb.append("\n[图片文字]\n").append(text.trim()).append("\n");
                                }
                            } finally {
                                image.flush();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("DOC 图片 OCR 失败: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取 DOC 内嵌图片失败: {}, {}", filePath, e.getMessage());
        }
        return sb.toString();
    }

    /** 是否为常见光栅图片格式 */
    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".bmp") || lower.endsWith(".gif")
            || lower.endsWith(".tiff") || lower.endsWith(".tif");
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
     * 将文本转换为结构化 Markdown。
     * 标题检测基于中文文档模式（第X章、编号开头等），过滤页码/分隔线等噪声，代码行保留原样。
     */
    public String toMarkdown(String plainText) {
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

            // 表格行（含多个制表符）
            if (trimmed.contains("\t")) {
                if (!inTable) { md.append("\n"); inTable = true; }
                md.append("| ").append(trimmed.replace("\t", " | ")).append(" |\n");
                continue;
            }

            // 过滤页码、分隔线、单字符等噪声
            if (isNoiseLine(trimmed)) continue;

            // 标题检测
            if (isHeading(trimmed)) {
                md.append("### ").append(trimmed).append("\n\n");
                continue;
            }

            md.append(trimmed).append("\n\n");
        }

        return md.toString().trim();
    }

    /** 是否为噪声行（页码、分隔线、极短文本） */
    private static boolean isNoiseLine(String line) {
        int len = line.trim().length();
        if (len <= 2) return true;
        // 纯数字/分隔符/制表符
        if (line.matches("^[\\d\\-—=/\\|\\*#~\\.\\s─-╿]+$")) return true;
        // PAGE 标识
        if (line.equalsIgnoreCase("PAGE")) return true;
        return false;
    }

    /** 是否匹配中文文档标题模式 */
    private static boolean isHeading(String line) {
        if (line.length() > 50) return false;

        // 句末非标题标点结尾 → 不是标题
        char last = line.charAt(line.length() - 1);
        if (last == '。' || last == '，' || last == '；' || last == '）' || last == ')') {
            return false;
        }

        // 代码/命令特征 → 不是标题
        if (isCodeLine(line)) return false;

        // "第X章/条/节/种/部分/类/项/款"
        if (line.matches("^第.{1,8}(章|条|节|种|部分|类|项|款|目).*")) return true;
        // 中文数字编号：一、二、
        if (line.matches("^[一二三四五六七八九十]+[、，,].*") && line.length() <= 25) return true;
        // 括号编号：（一）（1）
        if (line.matches("^[（(][一二三四五六七八九十\\d]+[）)]\\s*.*") && line.length() <= 30) return true;
        // 公文标题关键词
        if (line.matches("^(关于|根据|按照|为了|为贯彻|关于印发|转发).{2,30}$")) return true;
        // 纯中文短行（5-25 字，无标点）
        if (line.length() >= 5 && line.length() <= 25
                && line.matches("^[\\u4e00-\\u9fff\\w]+$")) return true;

        return false;
    }

    /** 是否为代码/命令特征行（路径、命令行参数、IP 等） */
    private static boolean isCodeLine(String line) {
        String lower = line.toLowerCase();
        return lower.contains("systemctl")
            || lower.contains(".service")
            || lower.contains("kill")
            || lower.contains("x11vnc")
            || lower.contains("cat ")
            || lower.contains("cat /")
            || lower.contains("/var/")
            || lower.contains("/usr/")
            || lower.contains("/root/")
            || lower.contains("/etc/")
            || lower.matches(".*\\s-[a-z]+(\\s|$).*")      // -flag style commands
            || lower.matches(".*\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*");  // IP
    }
}
