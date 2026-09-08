package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.LedgerThresholdSaveRequest;
import com.kk.biz.entity.FinLedgerThreshold;
import com.kk.biz.mapper.FinLedgerThresholdMapper;
import com.kk.biz.service.FinLedgerThresholdService;
import com.kk.common.exception.BusinessException;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FinLedgerThresholdServiceImpl extends ServiceImpl<FinLedgerThresholdMapper, FinLedgerThreshold>
        implements FinLedgerThresholdService {

    private static final BigDecimal DEFAULT_NOTIFY = new BigDecimal("5000.00");
    private static final BigDecimal DEFAULT_APPROVE = new BigDecimal("30000.00");

    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;

    @Override
    public FinLedgerThreshold getOrDefault(Long companyId) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        FinLedgerThreshold row = getById(companyId);
        if (row != null) {
            row.setConfigured(true);
            return row;
        }
        FinLedgerThreshold placeholder = new FinLedgerThreshold();
        placeholder.setCompanyId(companyId);
        placeholder.setEnabled(0);
        placeholder.setNotifyThreshold(DEFAULT_NOTIFY);
        placeholder.setApproveThreshold(DEFAULT_APPROVE);
        placeholder.setConfigured(false);
        return placeholder;
    }

    @Override
    public FinLedgerThreshold findEnabled(Long companyId) {
        if (companyId == null) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<FinLedgerThreshold>()
                .eq(FinLedgerThreshold::getCompanyId, companyId)
                .eq(FinLedgerThreshold::getEnabled, 1)
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(LedgerThresholdSaveRequest request) {
        if (request == null || request.getCompanyId() == null) {
            throw new BusinessException("请选择公司");
        }
        boolean isCompany = deptService.listCompanies().stream()
                .anyMatch(d -> d.getId() != null && d.getId().longValue() == request.getCompanyId().longValue());
        if (!isCompany) {
            throw new BusinessException("请选择有效的公司");
        }
        if (StpUtil.isLogin()) {
            Set<Long> visible = dataScopeService.visibleCompanyIds(StpUtil.getLoginIdAsLong());
            if (!visible.contains(request.getCompanyId())) {
                throw new BusinessException("无权配置该公司出账阈值");
            }
        }
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());
        BigDecimal notify = request.getNotifyThreshold() == null ? DEFAULT_NOTIFY : request.getNotifyThreshold();
        BigDecimal approve = request.getApproveThreshold() == null ? DEFAULT_APPROVE : request.getApproveThreshold();
        if (notify.compareTo(BigDecimal.ZERO) < 0 || approve.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("阈值不能为负数");
        }
        if (enabled && notify.compareTo(approve) > 0) {
            throw new BusinessException("通知线不能大于审批线");
        }

        FinLedgerThreshold row = getById(request.getCompanyId());
        if (row == null) {
            row = new FinLedgerThreshold();
            row.setCompanyId(request.getCompanyId());
            row.setEnabled(enabled ? 1 : 0);
            row.setNotifyThreshold(notify);
            row.setApproveThreshold(approve);
            row.setDeleted(0);
            // 显式走 MyBatis-Plus 实体保存，避免与本类 save(request) 重名混淆
            super.save(row);
        } else {
            row.setEnabled(enabled ? 1 : 0);
            row.setNotifyThreshold(notify);
            row.setApproveThreshold(approve);
            updateById(row);
        }
    }
}
