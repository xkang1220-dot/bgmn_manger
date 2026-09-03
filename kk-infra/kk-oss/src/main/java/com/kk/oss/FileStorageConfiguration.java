package com.kk.oss;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfiguration {

    @Bean
    public FileStorage fileStorage(FileStorageProperties properties) {
        String provider = properties.getProvider() == null ? "rustfs" : properties.getProvider().trim().toLowerCase();
        return switch (provider) {
            case RustFsFileStorage.STORAGE_TYPE -> {
                RustFsFileStorage storage = new RustFsFileStorage();
                FileStorageProperties.Rustfs rustfs = properties.getRustfs();
                storage.init(
                        rustfs.getEndpoint(),
                        rustfs.getAccessKey(),
                        rustfs.getSecretKey(),
                        rustfs.getBucket(),
                        rustfs.getDomain()
                );
                yield storage;
            }
            case MinioFileStorage.STORAGE_TYPE -> {
                MinioFileStorage storage = new MinioFileStorage();
                FileStorageProperties.Minio minio = properties.getMinio();
                storage.init(
                        minio.getEndpoint(),
                        minio.getAccessKey(),
                        minio.getSecretKey(),
                        minio.getBucket(),
                        minio.getDomain()
                );
                yield storage;
            }
            default -> {
                LocalFileStorage storage = new LocalFileStorage();
                storage.init(properties.getLocal().getBasePath());
                yield storage;
            }
        };
    }
}
