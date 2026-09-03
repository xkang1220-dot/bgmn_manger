package com.kk.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kk.common.result.Result;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final SysUserService userService;

    @GetMapping("/profile")
    public Result<SysUser> profile() {
        SysUser user = userService.getById(StpUtil.getLoginIdAsLong());
        if (user != null) {
            user.setPassword(null);
        }
        return Result.ok(user);
    }

    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody SysUser body) {
        SysUser update = new SysUser();
        update.setId(StpUtil.getLoginIdAsLong());
        update.setNickname(body.getNickname());
        update.setEmail(body.getEmail());
        update.setPhone(body.getPhone());
        update.setGender(body.getGender());
        update.setAvatar(body.getAvatar());
        userService.updateById(update);
        return Result.ok();
    }

    @PutMapping("/password")
    public Result<Void> password(@RequestBody Map<String, String> body) {
        Long id = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getById(id);
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || !cn.hutool.crypto.digest.BCrypt.checkpw(oldPassword, user.getPassword())) {
            throw new com.kk.common.exception.BusinessException("原密码不正确");
        }
        userService.resetPassword(id, newPassword);
        return Result.ok();
    }
}
