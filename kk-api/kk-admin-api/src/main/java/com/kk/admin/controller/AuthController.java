package com.kk.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.kk.common.exception.BusinessException;
import com.kk.common.result.Result;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysMenuService;
import com.kk.system.service.SysUserService;
import com.kk.system.totp.TotpService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService userService;
    private final SysMenuService menuService;
    private final TotpService totpService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginBody body) {
        SysUser user = userService.getByUsername(body.getUsername());
        if (user == null || !BCrypt.checkpw(body.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("账号已禁用");
        }
        totpService.validateTotpForLogin(user, body.getTotpCode());
        StpUtil.login(user.getId());
        user.setPassword(null);
        user.setTotpSecretKey(null);
        Map<String, Object> data = new HashMap<>();
        data.put("token", StpUtil.getTokenValue());
        data.put("user", user);
        return Result.ok(data);
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok();
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        user.setPassword(null);
        user.setTotpSecretKey(null);
        user.setRoleIds(userService.getRoleIds(userId));
        Map<String, Object> data = new HashMap<>();
        data.put("user", user);
        data.put("roles", userService.getRoleCodes(userId));
        data.put("permissions", userService.getPermissions(userId));
        data.put("menus", menuService.treeByUserId(userId));
        return Result.ok(data);
    }

    @Data
    public static class LoginBody {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        /** 两步验证码（启用 TOTP 时必填） */
        private String totpCode;
    }
}
