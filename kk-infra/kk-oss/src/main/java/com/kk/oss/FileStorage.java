package com.kk.oss;

import java.io.InputStream;

public interface FileStorage {

    String getStorageType();

    String upload(InputStream inputStream, String path, String fileName, String contentType, long size);

    void delete(String path);

    InputStream open(String path);

    String getUrl(String path);

    boolean exists(String path);
}
