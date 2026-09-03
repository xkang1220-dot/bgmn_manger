package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.dto.ApprovalQuery;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.dto.RollbackRequest;
import com.kk.biz.entity.WfApproval;

import java.util.List;

public interface WfApprovalService {

    WfApproval submit(ApprovalSubmitRequest request);

    Page<WfApproval> page(ApprovalQuery query);

    /** @deprecated 使用 {@link #page(ApprovalQuery)} */
    default Page<WfApproval> page(long page, long pageSize, String type, String status, String scope) {
        ApprovalQuery q = new ApprovalQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setType(type);
        q.setStatus(status);
        q.setScope(scope);
        return page(q);
    }

    WfApproval detail(Long id);

    void approve(Long id, String comment);

    void reject(Long id, String comment);

    void withdraw(Long id);

    void uploadReceipt(Long id, List<Long> fileIds);

    void confirmReceived(Long id);

    WfApproval submitRollback(RollbackRequest request);

    /** 扫描超时自动通过 */
    int autoPassTimeout();
}
