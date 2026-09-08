package com.kk.system.service;

import java.util.List;
import java.util.Set;

public interface DataScopeService {

    long GLOBAL_COMPANY_ID = 0L;

    boolean isGlobalAdmin(Long userId);

    Set<Long> visibleCompanyIds(Long userId);

    Set<Long> visibleUserIds(Long userId);

    boolean hasRoleInCompany(Long userId, String roleCode, Long companyId);

    List<Long> listUserIdsByRoleCodeInCompany(String roleCode, Long companyId);
}
