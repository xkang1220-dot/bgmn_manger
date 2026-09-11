package com.kk.system.service;

import java.util.List;
import java.util.Set;

public interface DataScopeService {

    long GLOBAL_COMPANY_ID = 0L;

    boolean isGlobalAdmin(Long userId);

    Set<Long> visibleCompanyIds(Long userId);

    /**
     * 所有可见公司下可见人员的并集（跨公司揉合，业务列表请优先用 {@link #visibleUserIdsInCompany}）。
     */
    Set<Long> visibleUserIds(Long userId);

    /**
     * 指定公司内，按该人在该公司各部门行的 data_scope 合并后的可见人员。
     * 不在该公司下无归属时返回空集。
     */
    Set<Long> visibleUserIdsInCompany(Long userId, Long companyId);

    boolean hasRoleInCompany(Long userId, String roleCode, Long companyId);

    List<Long> listUserIdsByRoleCodeInCompany(String roleCode, Long companyId);
}
