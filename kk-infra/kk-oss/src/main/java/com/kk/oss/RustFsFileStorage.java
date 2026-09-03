package com.kk.oss;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RustFsFileStorage implements FileStorage {

    public static final String STORAGE_TYPE = "rustfs";

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
                log.info("RustFS bucket 已创建: {}", bucketName);
            }
            log.info("RustFS 文件服务器初始化完成, endpoint={}, bucket={}", endpoint, bucketName);
        } catch (Exception e) {
            throw new IllegalStateException("RustFS 初始化失败", e);
        }
    }

    @Override
    public String upload(InputStream inputStream, String path, String fileName, String contentType, long size) {
        try {
            String objectName = normalizeObjectName(path, fileName);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Disposition", "inline");
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .headers(headers)
                    .stream(inputStream, size >= 0 ? size : -1, 10 * 1024 * 1024);
            String resolvedType = StringUtils.hasText(contentType) ? contentType : probeContentType(fileName);
            if (StringUtils.hasText(resolvedType)) {
                builder.contentType(resolvedType);
            }
            minioClient.putObject(builder.build());
            return getUrl(objectName);
        } catch (Exception e) {
            throw new IllegalStateException("RustFS 文件上传失败", e);
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
            log.warn("RustFS 文件删除失败: {}", path, e);
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
            throw new IllegalStateException("RustFS 文件读取失败: " + path, e);
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
            log.warn("RustFS 检查文件存在失败: {}", path, e);
            return false;
        }
    }

    private String probeContentType(String fileName) {
        try {
            String mimeType = Files.probeContentType(Paths.get(fileName));
            return mimeType != null ? mimeType : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
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
