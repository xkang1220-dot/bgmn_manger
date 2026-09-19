package com.kk.biz.ticket.support;

import cn.dev33.satoken.stp.StpUtil;
import com.kk.common.exception.BusinessException;
import com.kk.system.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TicketCompanyGuard {

    private final DataScopeService dataScopeService;

    public long loginId() {
        return StpUtil.getLoginIdAsLong();
    }

    public Set<Long> visibleCompanies() {
        long uid = loginId();
        if (dataScopeService.isGlobalAdmin(uid)) {
            return null; // null = all
        }
        Set<Long> ids = dataScopeService.visibleCompanyIds(uid);
        return ids == null ? Collections.emptySet() : new HashSet<>(ids);
    }

    public void assertCompanyWritable(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        long uid = loginId();
        if (dataScopeService.isGlobalAdmin(uid)) {
            return;
        }
        Set<Long> visible = dataScopeService.visibleCompanyIds(uid);
        if (visible == null || !visible.contains(companyId)) {
            throw new BusinessException(403, "无权操作该公司数据");
        }
    }

    public void assertCompanyVisible(Long companyId) {
        assertCompanyWritable(companyId);
    }

    public boolean canSeeCompany(Long companyId) {
        if (companyId == null) {
            return false;
        }
        long uid = loginId();
        if (dataScopeService.isGlobalAdmin(uid)) {
            return true;
        }
        Set<Long> visible = dataScopeService.visibleCompanyIds(uid);
        return visible != null && visible.contains(companyId);
    }
}
