package com.kk.system.totp;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

final class TotpSecretCryptoUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_GCM_IV_BYTES = 12;
    private static final int AES_GCM_TAG_BITS = 128;

    private TotpSecretCryptoUtils() {
    }

    static String encryptSecretKey(String secretKey, String masterKey) {
        if (StrUtil.isBlank(masterKey)) {
            throw new IllegalStateException("TOTP secret master key is blank");
        }
        try {
            byte[] iv = new byte[AES_GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(masterKey), "AES"),
                    new GCMParameterSpec(AES_GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secretKey.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.encode(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Encrypt TOTP secret failed", e);
        }
    }

    static String decryptSecretKey(String encryptedSecretKey, String masterKey) {
        if (StrUtil.isBlank(masterKey)) {
            throw new IllegalStateException("TOTP secret master key is blank");
        }
        try {
            byte[] payload = Base64.decode(encryptedSecretKey);
            byte[] iv = new byte[AES_GCM_IV_BYTES];
            byte[] encrypted = new byte[payload.length - AES_GCM_IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(masterKey), "AES"),
                    new GCMParameterSpec(AES_GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decrypt TOTP secret failed", e);
        }
    }

    private static byte[] deriveKey(String masterKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
    }
}
