package com.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF 智能解析引擎 —— 双轨并行：原生文字提取 + OCR 降级。
 *
 * <p>逐页处理流程：
 * <ol>
 *   <li>带坐标提取文字，按 Y 轴百分比过滤页眉页脚</li>
 *   <li>有效字符判定（字数 + Unicode 占比），不合格则整页 OCR</li>
 *   <li>单页异常隔离，失败不中断整文档</li>
 * </ol>
 */
@Slf4j
@Component
public class PdfParser {

    private final Tesseract tesseract;
    private final float headerRatio;
    private final float footerRatio;
    private final int minTextLength;
    private final double minValidRatio;
    private final float renderScale;

    public PdfParser(Tesseract tesseract,
                     @Value("${pdf.header-filter-top}") float headerRatio,
                     @Value("${pdf.footer-filter-bottom}") float footerRatio,
                     @Value("${pdf.min-text-length}") int minTextLength,
                     @Value("${pdf.min-valid-ratio}") double minValidRatio,
                     @Value("${ocr.dpi}") int dpi) {
        this.tesseract = tesseract;
        this.headerRatio = headerRatio;
        this.footerRatio = footerRatio;
        this.minTextLength = minTextLength;
        this.minValidRatio = minValidRatio;
        this.renderScale = dpi / 72f;
    }

    /** 解析结果：文本 + 质量元数据 */
    public record PdfParseResult(String text, int totalPages, int ocrPages, String qualityGrade) {}

    /** 处理单页结果：提取的文本 + 是否走了 OCR */
    private record PageResult(String text, boolean ocrUsed) {}

    /** 解析 PDF 文件，返回文本和质量元数据 */
    public PdfParseResult parse(Path filePath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            int totalPages = doc.getNumberOfPages();
            int ocrPages = 0;
            StringBuilder fullText = new StringBuilder();
            PDFRenderer renderer = new PDFRenderer(doc);

            for (int i = 0; i < totalPages; i++) {
                try {
                    PageResult result = processPage(doc, renderer, i);
                    if (result.text() != null && !result.text().isBlank()) {
                        fullText.append(result.text()).append("\n");
                    }
                    if (result.ocrUsed()) ocrPages++;
                } catch (Exception e) {
                    log.error("第 {} 页解析失败", i + 1, e);
                    fullText.append("[第").append(i + 1).append("页解析失败]\n");
                    ocrPages++;
                }
            }

            String text = fullText.toString().trim();
            boolean allOcr = ocrPages >= totalPages && totalPages > 0;
            String grade;
            if (ocrPages == 0) {
                grade = "A";
            } else if (ocrPages >= totalPages) {
                grade = "C";
            } else {
                grade = "B";
            }

            log.info("PDF 解析完成: {} 页, OCR {} 页, 质量等级 {}", totalPages, ocrPages, grade);
            return new PdfParseResult(text, totalPages, ocrPages, grade);
        }
    }

    /** 处理单页：文字提取 → 质量检查 → OCR 降级 */
    private PageResult processPage(PDDocument doc, PDFRenderer renderer, int pageIndex) throws IOException {
        PDPage page = doc.getPage(pageIndex);
        float pageHeight = page.getMediaBox().getHeight();

        // 1. 带坐标过滤的文字提取
        PageTextExtractor extractor = new PageTextExtractor(pageHeight, headerRatio, footerRatio);
        extractor.setStartPage(pageIndex + 1);
        extractor.setEndPage(pageIndex + 1);
        extractor.getText(doc);

        // 2. 用 RAW 文本做质量判定（避免页眉有文字但正文是扫描件时漏判）
        String rawText = extractor.getRawText();

        // 3. 质量判定（用 trimmed 消除首尾空白对字数的影响）
        String trimmed = rawText.trim();
        if (needsOcr(trimmed)) {
            log.debug("第 {} 页触发 OCR ({} 字符, 有效比 {}%)",
                    pageIndex + 1, trimmed.length(), Math.round(validRatio(trimmed) * 100));
            return new PageResult(ocrPage(renderer, pageIndex), true);
        }

        // 4. 原生文字达标，返回过滤页眉页脚后的文本
        return new PageResult(extractor.getBodyText(), false);
    }

    /** 质量判定：字数不足 或 有效字符占比低于阈值 → 需要 OCR */
    private boolean needsOcr(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < minTextLength) return true;
        return validRatio(trimmed) < minValidRatio;
    }

    /** 计算有效字符占比（汉字 + ASCII 可见字符 + 常用中文标点） */
    private double validRatio(String text) {
        int valid = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isValidChar(text.charAt(i))) valid++;
        }
        return (double) valid / text.length();
    }

    /** 判断是否为有效字符 */
    @SuppressWarnings("StatementWithEmptyBody")
    private static boolean isValidChar(char c) {
        // 标准汉字
        if (c >= '一' && c <= '鿿') return true;
        // ASCII 可见字符
        if (c >= 0x20 && c <= 0x7E) return true;
        // 中文标点
        switch (c) {
            case '，': // ，
            case '。': // 。
            case '；': // ；
            case '：': // ：
            case '！': // ！
            case '？': // ？
            case '“': // "
            case '”': // "
            case '‘': // '
            case '’': // '
            case '（': // （
            case '）': // ）
            case '【': // 【
            case '】': // 】
            case '《': // 《
            case '》': // 》
            case '、': // 、
            case '…': // …
            case '—': // —
                return true;
            default:
                return false;
        }
    }

    /** 渲染页面为图像并 OCR 识别 */
    private String ocrPage(PDFRenderer renderer, int pageIndex) throws IOException {
        BufferedImage image = renderer.renderImage(pageIndex, renderScale);
        try {
            String result = tesseract.doOCR(image);
            return result != null ? result.trim() : "";
        } catch (TesseractException e) {
            throw new IOException("OCR 识别失败: 第" + (pageIndex + 1) + "页", e);
        } finally {
            image.flush();
        }
    }

    /** 带坐标的文字提取器 —— 捕获 TextPosition 用于页眉页脚过滤 */
    private static class PageTextExtractor extends PDFTextStripper {
        private final List<TextPosition> allPositions = new ArrayList<>();
        private final float pageHeight;
        private final float topThreshold;
        private final float bottomThreshold;

        PageTextExtractor(float pageHeight, float headerRatio, float footerRatio) {
            this.pageHeight = pageHeight;
            this.topThreshold = pageHeight * (1f - headerRatio);
            this.bottomThreshold = pageHeight * footerRatio;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            allPositions.addAll(textPositions);
        }

        /** 获取原始文本（含页眉页脚，用于质量判定） */
        String getRawText() {
            if (allPositions.isEmpty()) return "";
            List<TextPosition> sorted = sortByReadingOrder(allPositions);
            return positionsToText(sorted);
        }

        /** 获取正文文本（过滤页眉页脚后） */
        String getBodyText() {
            List<TextPosition> body = allPositions.stream()
                    .filter(p -> p.getY() >= bottomThreshold && p.getY() <= topThreshold)
                    .collect(Collectors.toList());
            if (body.isEmpty()) return "";
            return positionsToText(sortByReadingOrder(body));
        }

        /** 按阅读顺序排序：Y 降序（从上到下），X 升序（从左到右） */
        private static List<TextPosition> sortByReadingOrder(List<TextPosition> positions) {
            List<TextPosition> sorted = new ArrayList<>(positions);
            sorted.sort(Comparator
                    .comparingDouble(TextPosition::getY).reversed()
                    .thenComparingDouble(TextPosition::getX));
            return sorted;
        }

        /** 把排序后的位置列表拼接为文本，Y 坐标跳变时插入换行 */
        private static String positionsToText(List<TextPosition> positions) {
            StringBuilder sb = new StringBuilder();
            float lastY = Float.NaN;
            for (TextPosition tp : positions) {
                if (!Float.isNaN(lastY) && Math.abs(tp.getY() - lastY) > 3f) {
                    sb.append('\n');
                }
                sb.append(tp.getUnicode());
                lastY = tp.getY();
            }
            return sb.toString();
        }
    }
}
