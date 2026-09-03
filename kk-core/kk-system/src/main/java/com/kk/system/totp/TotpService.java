package com.kk.system.totp;

import cn.dev33.satoken.secure.totp.SaTotpTemplate;
import cn.dev33.satoken.secure.totp.SaTotpUtil;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TotpService {

    private static final int TOTP_VALIDATE_WINDOW = 1;
    private static final int TOTP_CODE_DIGITS = 6;
    private static final int TOTP_SECRET_BYTES = 20;
    private static final int TOTP_STEP_SECONDS = 30;
    private static final int TOTP_REPLAY_TTL_BUFFER_SECONDS = 5;
    private static final int TOTP_PENDING_SECRET_MINUTES = 10;
    private static final int TOTP_ENABLED = 1;
    private static final LoginTotpTemplate LOGIN_TOTP_TEMPLATE = new LoginTotpTemplate();

    private final SysUserService userService;
    private final TotpCache totpCache;

    @Value("${kk.totp.secret-master-key:KkTotp~SecretKey2026!ChangeMe}")
    private String totpSecretMasterKey;

    @Value("${kk.totp.issuer:KK Manager}")
    private String totpIssuer;

    public TotpGenerateResponse generateTotp() {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = getCurrentUser(userId);
        if (isTotpEnabled(user)) {
            throw new BusinessException("用户已绑定两步验证，不能重复生成");
        }

        String secretKey = SaTotpUtil.generateSecretKey();
        String accountName = StrUtil.blankToDefault(user.getUsername(), String.valueOf(userId));
        String qrString = SaTotpUtil.generateGoogleSecretKey(accountName, totpIssuer, secretKey);

        String encryptedSecretKey = TotpSecretCryptoUtils.encryptSecretKey(secretKey, totpSecretMasterKey);
        totpCache.set(
                totpCache.pendingKey(userId),
                encryptedSecretKey,
                TimeUnit.MINUTES.toSeconds(TOTP_PENDING_SECRET_MINUTES)
        );

        TotpGenerateResponse response = new TotpGenerateResponse();
        response.setAccountName(accountName);
        response.setSecretKey(secretKey);
        response.setQrString(qrString);
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean verifyTotp(TotpVerifyRequest request) {
        String totpCode = request == null ? null : normalizeCode(request.getTotpCode());
        if (!isSixDigitCode(totpCode)) {
            throw new BusinessException("验证码不能为空且必须为6位数字");
        }

        long userId = StpUtil.getLoginIdAsLong();
        getCurrentUser(userId);

        String pendingKey = totpCache.pendingKey(userId);
        String encryptedPendingSecretKey = totpCache.get(pendingKey);
        if (StrUtil.isBlank(encryptedPendingSecretKey)) {
            throw new BusinessException("请先生成两步验证绑定信息");
        }

        String secretKey = TotpSecretCryptoUtils.decryptSecretKey(encryptedPendingSecretKey, totpSecretMasterKey);
        if (!SaTotpUtil.validateTOTP(secretKey, totpCode, TOTP_VALIDATE_WINDOW)) {
            throw new BusinessException("验证码错误");
        }

        SysUser update = new SysUser();
        update.setId(userId);
        update.setTotpSecretKey(TotpSecretCryptoUtils.encryptSecretKey(secretKey, totpSecretMasterKey));
        update.setTotpEnabled(TOTP_ENABLED);
        update.setTotpVerifyTime(LocalDateTime.now());
        boolean updated = userService.updateById(update);
        totpCache.delete(pendingKey);
        return updated;
    }

    public Boolean getCurrentUserTotpEnabled() {
        long userId = StpUtil.getLoginIdAsLong();
        return getCurrentUserTotpEnabled(userId);
    }

    public Boolean getCurrentUserTotpEnabled(Long userId) {
        if (userId == null) {
            return false;
        }
        return isTotpEnabled(userService.getById(userId));
    }

    public Boolean getUserTotpEnabledByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return false;
        }
        return isTotpEnabled(userService.getByUsername(username.trim()));
    }

    public void validateTotpForLogin(SysUser user, String totpCode) {
        String normalizedCode = normalizeCode(totpCode);
        if (user == null || !isTotpEnabled(user)) {
            return;
        }
        if (!isSixDigitCode(normalizedCode)) {
            throw new BusinessException("请输入6位两步验证码");
        }

        String secretKey = TotpSecretCryptoUtils.decryptSecretKey(user.getTotpSecretKey(), totpSecretMasterKey);
        long currentEpochSeconds = Instant.now().getEpochSecond();
        long currentStep = currentEpochSeconds / TOTP_STEP_SECONDS;
        Long matchedStep = matchTotpStep(secretKey, normalizedCode, currentStep);
        if (matchedStep == null) {
            throw new BusinessException("两步验证码错误");
        }

        long ttlSeconds = Math.max(
                TOTP_REPLAY_TTL_BUFFER_SECONDS,
                ((matchedStep + 2) * TOTP_STEP_SECONDS) - currentEpochSeconds + TOTP_REPLAY_TTL_BUFFER_SECONDS
        );
        String replayKey = totpCache.replayKey(user.getId(), matchedStep);
        boolean saved = totpCache.setIfAbsent(
                replayKey,
                SecureUtil.md5(normalizedCode),
                ttlSeconds
        );
        if (!saved) {
            throw new BusinessException("两步验证码已使用，请等待新验证码");
        }
    }

    public boolean isTotpEnabled(SysUser user) {
        return user != null
                && Integer.valueOf(TOTP_ENABLED).equals(user.getTotpEnabled())
                && StrUtil.isNotBlank(user.getTotpSecretKey());
    }

    private SysUser getCurrentUser(long userId) {
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.trim().replace(" ", "");
    }

    private boolean isSixDigitCode(String code) {
        return StrUtil.isNotBlank(code) && code.matches("\\d{" + TOTP_CODE_DIGITS + "}");
    }

    private Long matchTotpStep(String secretKey, String totpCode, long currentStep) {
        if (totpCode.equals(generateTotpCode(secretKey, currentStep))) {
            return currentStep;
        }
        long previousStep = currentStep - 1;
        if (previousStep >= 0 && totpCode.equals(generateTotpCode(secretKey, previousStep))) {
            return previousStep;
        }
        return null;
    }

    private String generateTotpCode(String secretKey, long timeStep) {
        return LOGIN_TOTP_TEMPLATE.generateAt(secretKey, timeStep * TOTP_STEP_SECONDS);
    }

    private static class LoginTotpTemplate extends SaTotpTemplate {

        private LoginTotpTemplate() {
            super(TOTP_STEP_SECONDS, TOTP_CODE_DIGITS, "HmacSHA1", TOTP_SECRET_BYTES);
        }

        private String generateAt(String secretKey, long epochSeconds) {
            return _generateTOTP(secretKey, epochSeconds);
        }
    }
}
