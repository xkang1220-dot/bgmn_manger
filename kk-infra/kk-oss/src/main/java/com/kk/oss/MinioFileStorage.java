package com.kk.oss;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

@Slf4j
public class MinioFileStorage implements FileStorage {

    public static final String STORAGE_TYPE = "minio";

    private MinioClient minioClient;
    private String bucketName;
    private String domain;

    @Override
    public String getStorageType() {
        return STORAGE_TYPE;
    }

    public void init(String endpoint, String accessKey, String secretKey, String bucketName, String domain) {
        this.bucketName = bucketName;
        this.domain = trimTrailingSlash(domain);
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("MinIO bucket 已创建: {}", bucketName);
            }
            log.info("MinIO 文件服务器初始化完成, endpoint={}, bucket={}", endpoint, bucketName);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 初始化失败", e);
        }
    }

    @Override
    public String upload(InputStream inputStream, String path, String fileName, String contentType, long size) {
        try {
            String objectName = normalizeObjectName(path, fileName);
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, size >= 0 ? size : -1, 10 * 1024 * 1024);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            minioClient.putObject(builder.build());
            return getUrl(objectName);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 文件上传失败", e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build());
        } catch (Exception e) {
            log.warn("MinIO 文件删除失败: {}", path, e);
        }
    }

    @Override
    public InputStream open(String path) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 文件读取失败: " + path, e);
        }
    }

    @Override
    public String getUrl(String path) {
        String objectName = path.replace("\\", "/");
        while (objectName.startsWith("/")) {
            objectName = objectName.substring(1);
        }
        return domain + "/" + bucketName + "/" + objectName;
    }

    @Override
    public boolean exists(String path) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            log.warn("MinIO 检查文件存在失败: {}", path, e);
            return false;
        }
    }

    private String normalizeObjectName(String path, String fileName) {
        String dir = path == null ? "" : path.replace("\\", "/");
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.isEmpty() ? fileName : dir + "/" + fileName;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
