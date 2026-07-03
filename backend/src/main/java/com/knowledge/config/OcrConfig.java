package com.knowledge.config;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.awt.image.BufferedImage;

/** OCR 引擎配置 —— Tesseract 实例管理 */
@Slf4j
@Configuration
public class OcrConfig {

    @Value("${ocr.tessdata-path}")
    private String tessdataPath;

    @Value("${ocr.language}")
    private String language;

    /** 创建 Tesseract 实例（同步包装），配置中文简体+英文识别 */
    @Bean
    public Tesseract tesseract() {
        Tesseract tesseract = new Tesseract() {
            @Override
            public synchronized String doOCR(BufferedImage image) throws TesseractException {
                return super.doOCR(image);
            }
        };
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(language);
        log.info("Tesseract OCR 已初始化 — tessdata: {}, language: {}", tessdataPath, language);
        return tesseract;
    }
}
