package com.kk.system.config;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserService userService;

    private static final String CACHE_KEY_PERMISSIONS = "user_permissions";
    private static final String CACHE_KEY_ROLES = "user_roles";

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        SaSession session = StpUtil.getSessionByLoginId(loginId);
        return session.get(CACHE_KEY_PERMISSIONS, () -> userService.getPermissions(Long.parseLong(loginId.toString())));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SaSession session = StpUtil.getSessionByLoginId(loginId);
        return session.get(CACHE_KEY_ROLES, () -> userService.getRoleCodes(Long.parseLong(loginId.toString())));
    }
}
