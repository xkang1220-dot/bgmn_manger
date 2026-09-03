package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.LedgerQuery;
import com.kk.biz.dto.ProjectManualSettleRequest;
import com.kk.biz.dto.ProjectSettleRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinPool;

import java.math.BigDecimal;
import java.util.Map;

public interface FinanceService extends IService<FinPool> {

    FinPool getDefaultPool();

    void createPool(FinPool pool);

    void updatePool(FinPool pool);

    Page<FinLedger> pageLedger(LedgerQuery query);

    /** @deprecated 使用 {@link #pageLedger(LedgerQuery)} */
    default Page<FinLedger> pageLedger(long page, long pageSize, String bizType, String accountType, Long userId, Long poolId, Long projectId) {
        LedgerQuery q = new LedgerQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setBizType(bizType);
        q.setAccountType(accountType);
        q.setUserId(userId);
        q.setPoolId(poolId);
        q.setProjectId(projectId);
        return pageLedger(q);
    }

    void createLedger(LedgerCreateRequest request);

    /**
     * 回退总账入账：按净额扣回资金池与渠道（原入账为总额−手续费）。
     *
     * @param rollbackGross 本次回退对应的「原入账总额」部分（全额回退时等于原总额）
     */
    void reverseIncomeRegister(Long poolId, Long channelId, BigDecimal rollbackGross,
                               BigDecimal originGross, String feeMode, BigDecimal feeValue,
                               Long approvalId, String title, String remark);

    void settleProject(ProjectSettleRequest request);

    void settleProjectManual(ProjectManualSettleRequest request);

    Map<String, Object> summary();
}
