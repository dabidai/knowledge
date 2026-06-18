package com.knowledge.service;

import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

/** MinIO 文件存储服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-docs}")
    private String bucketDocs;

    @Value("${minio.bucket-markdown}")
    private String bucketMd;

    @Value("${minio.presigned-expiry}")
    private int presignedExpiry;

    /** 上传原始文档 */
    public String uploadDocument(String objectPath, InputStream data, long size, String contentType) {
        try {
            ensureBucket(bucketDocs);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketDocs)
                    .object(objectPath)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
            log.debug("文档上传成功: {}", objectPath);
            return objectPath;
        } catch (Exception e) {
            throw new RuntimeException("上传文件失败: " + objectPath, e);
        }
    }

    /** 上传解析后的 Markdown */
    public String uploadMarkdown(String fileId, String markdown) {
        try {
            ensureBucket(bucketMd);
            String objectPath = "markdown/" + fileId + ".md";
            byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketMd)
                    .object(objectPath)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/markdown; charset=utf-8")
                    .build());
            log.debug("Markdown 上传成功: {}", objectPath);
            return objectPath;
        } catch (Exception e) {
            throw new RuntimeException("上传 Markdown 失败: " + fileId, e);
        }
    }

    /** 生成预签名下载 URL */
    public String getPresignedUrl(String objectPath) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketDocs)
                            .object(objectPath)
                            .expiry(presignedExpiry, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            log.error("生成预签名 URL 失败: {}", objectPath, e);
            return null;
        }
    }

    /** 获取原始文档流（用于下载代理） */
    public InputStream getDocumentStream(String objectPath) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketDocs)
                            .object(objectPath)
                            .build());
        } catch (Exception e) {
            log.error("读取文档失败: {}", objectPath, e);
            return null;
        }
    }

    /** 获取文档的 Content-Type（用于下载代理设置响应头） */
    public String getDocumentContentType(String objectPath) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketDocs)
                            .object(objectPath)
                            .build());
            return stat.contentType();
        } catch (Exception e) {
            log.warn("获取文档 Content-Type 失败: {}, 使用默认值", objectPath);
            return "application/octet-stream";
        }
    }

    /** 获取 Markdown 内容 */
    public String getMarkdown(String objectPath) {
        try {
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketMd)
                            .object(objectPath)
                            .build());
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("读取 Markdown 失败: {}", objectPath, e);
            return null;
        }
    }

    /** 确保 Bucket 存在 */
    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("创建 Bucket: {}", bucket);
        }
    }
}
