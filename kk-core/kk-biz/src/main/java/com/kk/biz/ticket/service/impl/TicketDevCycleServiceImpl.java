package com.kk.biz.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kk.biz.ticket.entity.TicketDevCycle;
import com.kk.biz.ticket.mapper.TicketDevCycleMapper;
import com.kk.biz.ticket.service.TicketDevCycleService;
import com.kk.biz.ticket.support.TicketCompanyGuard;
import com.kk.biz.ticket.support.TicketValidation;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketDevCycleServiceImpl implements TicketDevCycleService {

    private final TicketDevCycleMapper cycleMapper;
    private final TicketCompanyGuard companyGuard;

    @Override
    public List<TicketDevCycle> list(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        companyGuard.assertCompanyVisible(companyId);
        return cycleMapper.selectList(new LambdaQueryWrapper<TicketDevCycle>()
                .eq(TicketDevCycle::getCompanyId, companyId)
                .orderByDesc(TicketDevCycle::getStartDate)
                .orderByDesc(TicketDevCycle::getId));
    }

    @Override
    public TicketDevCycle active(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        companyGuard.assertCompanyVisible(companyId);
        return cycleMapper.selectOne(new LambdaQueryWrapper<TicketDevCycle>()
                .eq(TicketDevCycle::getCompanyId, companyId)
                .eq(TicketDevCycle::getStatus, "active")
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDevCycle create(TicketDevCycle cycle) {
        validateCycle(cycle, true);
        companyGuard.assertCompanyWritable(cycle.getCompanyId());
        cycle.setId(null);
        cycle.setStatus(TicketValidation.requireCycleStatus(
                StringUtils.hasText(cycle.getStatus()) ? cycle.getStatus() : "planning"));
        if ("active".equals(cycle.getStatus())) {
            deactivateOthers(cycle.getCompanyId(), null);
        }
        cycleMapper.insert(cycle);
        return cycle;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDevCycle update(Long id, TicketDevCycle cycle) {
        TicketDevCycle db = cycleMapper.selectById(id);
        if (db == null) {
            throw new BusinessException(404, "开发周期不存在");
        }
        companyGuard.assertCompanyWritable(db.getCompanyId());
        if (cycle == null) {
            throw new BusinessException(400, "参数不能为空");
        }
        if (StringUtils.hasText(cycle.getName())) {
            db.setName(cycle.getName().trim());
        }
        if (cycle.getStartDate() != null) {
            db.setStartDate(cycle.getStartDate());
        }
        if (cycle.getEndDate() != null) {
            db.setEndDate(cycle.getEndDate());
        }
        if (cycle.getDescription() != null) {
            db.setDescription(cycle.getDescription());
        }
        if (StringUtils.hasText(cycle.getStatus())) {
            db.setStatus(TicketValidation.requireCycleStatus(cycle.getStatus()));
            if ("active".equals(db.getStatus())) {
                deactivateOthers(db.getCompanyId(), db.getId());
            }
        }
        if (db.getStartDate() != null && db.getEndDate() != null && db.getEndDate().isBefore(db.getStartDate())) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
        cycleMapper.updateById(db);
        return db;
    }

    private void validateCycle(TicketDevCycle cycle, boolean creating) {
        if (cycle == null || !StringUtils.hasText(cycle.getName())) {
            throw new BusinessException(400, "周期名称不能为空");
        }
        if (cycle.getCompanyId() == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        if (cycle.getStartDate() == null || cycle.getEndDate() == null) {
            throw new BusinessException(400, "请填写起止日期");
        }
        if (cycle.getEndDate().isBefore(cycle.getStartDate())) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
        cycle.setName(cycle.getName().trim());
    }

    private void deactivateOthers(Long companyId, Long keepId) {
        LambdaUpdateWrapper<TicketDevCycle> uw = new LambdaUpdateWrapper<TicketDevCycle>()
                .eq(TicketDevCycle::getCompanyId, companyId)
                .eq(TicketDevCycle::getStatus, "active")
                .ne(keepId != null, TicketDevCycle::getId, keepId)
                .set(TicketDevCycle::getStatus, "completed");
        cycleMapper.update(null, uw);
    }
}
