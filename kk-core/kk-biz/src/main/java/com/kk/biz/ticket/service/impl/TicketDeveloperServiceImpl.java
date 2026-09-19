package com.kk.biz.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kk.biz.ticket.entity.TicketDeveloper;
import com.kk.biz.ticket.entity.TicketWorkOrderAssignee;
import com.kk.biz.ticket.mapper.TicketDeveloperMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderAssigneeMapper;
import com.kk.biz.ticket.service.TicketDeveloperService;
import com.kk.biz.ticket.support.TicketCompanyGuard;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TicketDeveloperServiceImpl implements TicketDeveloperService {

    private final TicketDeveloperMapper developerMapper;
    private final TicketWorkOrderAssigneeMapper assigneeMapper;
    private final TicketCompanyGuard companyGuard;

    @Override
    public List<TicketDeveloper> list(Long companyId, Integer status) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        companyGuard.assertCompanyVisible(companyId);
        return developerMapper.selectList(new LambdaQueryWrapper<TicketDeveloper>()
                .eq(TicketDeveloper::getCompanyId, companyId)
                .eq(status != null, TicketDeveloper::getStatus, status)
                .orderByAsc(TicketDeveloper::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDeveloper create(TicketDeveloper developer) {
        if (developer == null || !StringUtils.hasText(developer.getName())) {
            throw new BusinessException(400, "姓名不能为空");
        }
        companyGuard.assertCompanyWritable(developer.getCompanyId());
        developer.setId(null);
        if (developer.getStatus() == null) {
            developer.setStatus(1);
        }
        developer.setName(developer.getName().trim());
        developerMapper.insert(developer);
        return developer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDeveloper update(Long id, Map<String, Object> body) {
        TicketDeveloper db = developerMapper.selectById(id);
        if (db == null) {
            throw new BusinessException(404, "开发人员不存在");
        }
        companyGuard.assertCompanyWritable(db.getCompanyId());
        if (body == null || body.isEmpty()) {
            return db;
        }
        if (body.containsKey("name") && body.get("name") != null) {
            String name = String.valueOf(body.get("name")).trim();
            if (!StringUtils.hasText(name)) {
                throw new BusinessException(400, "姓名不能为空");
            }
            db.setName(name);
        }
        if (body.containsKey("role")) {
            Object role = body.get("role");
            db.setRole(role == null || !StringUtils.hasText(String.valueOf(role))
                    ? null : String.valueOf(role).trim());
        }
        developerMapper.updateById(db);

        if (body.containsKey("sysUserId")) {
            Object raw = body.get("sysUserId");
            if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
                developerMapper.update(null, new LambdaUpdateWrapper<TicketDeveloper>()
                        .eq(TicketDeveloper::getId, id)
                        .setSql("sys_user_id = NULL"));
            } else {
                developerMapper.update(null, new LambdaUpdateWrapper<TicketDeveloper>()
                        .eq(TicketDeveloper::getId, id)
                        .set(TicketDeveloper::getSysUserId, Long.valueOf(String.valueOf(raw))));
            }
        }
        if (body.containsKey("role") && (body.get("role") == null
                || !StringUtils.hasText(String.valueOf(body.get("role"))))) {
            developerMapper.update(null, new LambdaUpdateWrapper<TicketDeveloper>()
                    .eq(TicketDeveloper::getId, id)
                    .setSql("role = NULL"));
        }
        return developerMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TicketDeveloper db = developerMapper.selectById(id);
        if (db == null) {
            throw new BusinessException(404, "开发人员不存在");
        }
        companyGuard.assertCompanyWritable(db.getCompanyId());
        assigneeMapper.delete(new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                .eq(TicketWorkOrderAssignee::getDeveloperId, id));
        developerMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "状态不合法");
        }
        TicketDeveloper db = developerMapper.selectById(id);
        if (db == null) {
            throw new BusinessException(404, "开发人员不存在");
        }
        companyGuard.assertCompanyWritable(db.getCompanyId());
        db.setStatus(status);
        developerMapper.updateById(db);
    }
}
