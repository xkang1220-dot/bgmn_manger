package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.kk.common.result.Result;
import com.kk.system.totp.TotpGenerateResponse;
import com.kk.system.totp.TotpService;
import com.kk.system.totp.TotpVerifyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/totp")
@RequiredArgsConstructor
public class TotpController {

    private final TotpService totpService;

    @SaCheckLogin
    @PostMapping("/generate")
    public Result<TotpGenerateResponse> generateTotp() {
        return Result.ok(totpService.generateTotp());
    }

    @SaCheckLogin
    @PostMapping("/verify")
    public Result<Boolean> verifyTotp(@RequestBody TotpVerifyRequest request) {
        return Result.ok(totpService.verifyTotp(request));
    }

    @SaCheckLogin
    @GetMapping("/status")
    public Result<Boolean> getCurrentUserTotpEnabled() {
        return Result.ok(totpService.getCurrentUserTotpEnabled());
    }

    @GetMapping("/status/user/{userId}")
    public Result<Boolean> getUserTotpEnabled(@PathVariable Long userId) {
        return Result.ok(totpService.getCurrentUserTotpEnabled(userId));
    }

    @GetMapping("/status/username")
    public Result<Boolean> getUserTotpEnabledByUsername(@RequestParam String username) {
        return Result.ok(totpService.getUserTotpEnabledByUsername(username));
    }
}
