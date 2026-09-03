package com.kk.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "kk.file.storage")
public class FileStorageProperties {

    /** local | minio | rustfs */
    private String provider = "rustfs";

    /** 桶内通用路径前缀 */
    private String prefix = "kk-files";

    /** 进出账凭证路径前缀 */
    private String ledgerVoucherPrefix = "ledger-vouchers";

    /** 单文件大小上限（MB） */
    private int maxSizeMb = 100;

    private Local local = new Local();

    private Minio minio = new Minio();

    private Rustfs rustfs = new Rustfs();

    @Data
    public static class Local {
        private String basePath = "./uploads";
    }

    @Data
    public static class Minio {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "kk-manager";
        private String domain = "http://127.0.0.1:9000";
    }

    @Data
    public static class Rustfs {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "kk-manager";
        private String domain = "http://127.0.0.1:9000";
    }

    public String buildStoragePath(String datePath) {
        return buildStoragePath(datePath, null);
    }

    public String buildStoragePath(String datePath, String bizType) {
        String basePrefix = "ledger_voucher".equals(bizType) ? ledgerVoucherPrefix : prefix;
        String normalizedPrefix = basePrefix == null ? "" : basePrefix.trim().replace("\\", "/");
        while (normalizedPrefix.startsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(1);
        }
        while (normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        if (normalizedPrefix.isEmpty()) {
            return datePath;
        }
        return normalizedPrefix + "/" + datePath;
    }
}
