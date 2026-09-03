package com.kk.oss;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
public class LocalFileStorage implements FileStorage {

    public static final String STORAGE_TYPE = "local";

    private String basePath;

    @Override
    public String getStorageType() {
        return STORAGE_TYPE;
    }

    public void init(String basePath) {
        this.basePath = basePath;
        try {
            Files.createDirectories(Paths.get(basePath));
            log.info("本地文件存储初始化完成, path={}", basePath);
        } catch (IOException e) {
            throw new IllegalStateException("创建本地存储目录失败: " + basePath, e);
        }
    }

    @Override
    public String upload(InputStream inputStream, String path, String fileName, String contentType, long size) {
        try {
            Path fullPath = Paths.get(basePath, path, fileName);
            Files.createDirectories(fullPath.getParent());
            Files.copy(inputStream, fullPath, StandardCopyOption.REPLACE_EXISTING);
            return getUrl(path + "/" + fileName);
        } catch (IOException e) {
            throw new IllegalStateException("本地文件上传失败", e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(Paths.get(basePath, path));
        } catch (IOException e) {
            log.warn("本地文件删除失败: {}", path, e);
        }
    }

    @Override
    public InputStream open(String path) {
        try {
            return Files.newInputStream(Paths.get(basePath, path));
        } catch (IOException e) {
            throw new IllegalStateException("本地文件读取失败: " + path, e);
        }
    }

    @Override
    public String getUrl(String path) {
        return null;
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(Paths.get(basePath, path));
    }

    public Path getFullPath(String path) {
        return Paths.get(basePath, path).toAbsolutePath().normalize();
    }
}
