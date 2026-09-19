package com.kk.biz.ticket.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.entity.PmProject;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.ticket.dto.TicketAssignRequest;
import com.kk.biz.ticket.dto.TicketBatchUpdateRequest;
import com.kk.biz.ticket.dto.TicketCreateRequest;
import com.kk.biz.ticket.dto.TicketProgressRequest;
import com.kk.biz.ticket.dto.TicketUpdateRequest;
import com.kk.biz.ticket.entity.TicketDevCycle;
import com.kk.biz.ticket.entity.TicketDeveloper;
import com.kk.biz.ticket.entity.TicketNumberSequence;
import com.kk.biz.ticket.entity.TicketProgressLog;
import com.kk.biz.ticket.entity.TicketWorkOrder;
import com.kk.biz.ticket.entity.TicketWorkOrderAssignee;
import com.kk.biz.ticket.mapper.TicketDevCycleMapper;
import com.kk.biz.ticket.mapper.TicketDeveloperMapper;
import com.kk.biz.ticket.mapper.TicketNumberSequenceMapper;
import com.kk.biz.ticket.mapper.TicketProgressLogMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderAssigneeMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderMapper;
import com.kk.biz.ticket.service.TicketReplyService;
import com.kk.biz.ticket.service.TicketWorkOrderService;
import com.kk.biz.ticket.support.TicketAccessHelper;
import com.kk.biz.ticket.support.TicketAttributeUpdater;
import com.kk.biz.ticket.support.TicketCompanyGuard;
import com.kk.biz.ticket.support.TicketValidation;
import com.kk.biz.ticket.vo.TicketVO;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketWorkOrderServiceImpl implements TicketWorkOrderService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final TicketWorkOrderMapper workOrderMapper;
    private final TicketNumberSequenceMapper sequenceMapper;
    private final TicketWorkOrderAssigneeMapper assigneeMapper;
    private final TicketDeveloperMapper developerMapper;
    private final TicketDevCycleMapper cycleMapper;
    private final TicketProgressLogMapper progressLogMapper;
    private final TicketReplyService replyService;
    private final TicketCompanyGuard companyGuard;
    private final TicketAccessHelper accessHelper;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final PmProjectService projectService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO create(TicketCreateRequest request) {
        if (request == null) {
            throw new BusinessException(400, "参数不能为空");
        }
        companyGuard.assertCompanyWritable(request.getCompanyId());
        TicketValidation.requireTitle(request.getTitle());
        String type = TicketValidation.requireType(request.getType());
        String urgency = TicketValidation.requireUrgency(request.getUrgency());
        TicketValidation.requireDescription(request.getDescription());

        long loginId = companyGuard.loginId();
        SysUser user = userService.getById(loginId);
        String nickname = user != null && StringUtils.hasText(user.getNickname())
                ? user.getNickname() : (user != null ? user.getUsername() : String.valueOf(loginId));

        TicketWorkOrder order = new TicketWorkOrder();
        order.setCompanyId(request.getCompanyId());
        order.setTitle(request.getTitle().trim());
        order.setType(type);
        order.setUrgency(urgency);
        order.setDescription(request.getDescription());
        order.setStatus("pending");
        order.setProgress(0);
        order.setSubmitterId(loginId);
        order.setSubmitterName(nickname);
        order.setTicketNo(nextTicketNo(request.getCompanyId()));
        order.setProjectId(resolveProjectId(request.getCompanyId(), request.getProjectId()));

        TicketDevCycle active = findActiveCycle(request.getCompanyId());
        if (active != null) {
            order.setCycleId(active.getId());
        }

        workOrderMapper.insert(order);
        TicketVO vo = toVo(order, true);
        fillAssigneesAndCycles(List.of(vo));
        return vo;
    }

    @Override
    public Page<TicketVO> page(long page, long pageSize, Long companyId, String title, String type,
                               String status, String urgency, Long developerId, Long cycleId,
                               Long submitterId, Long projectId) {
        Set<Long> visible = companyGuard.visibleCompanies();
        if (visible != null && visible.isEmpty()) {
            return emptyPage(page, pageSize);
        }
        if (companyId != null) {
            if (visible != null && !visible.contains(companyId)) {
                return emptyPage(page, pageSize);
            }
        }

        Set<Long> ticketIdsByDev = null;
        if (developerId != null) {
            ticketIdsByDev = assigneeMapper.selectList(new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                            .eq(TicketWorkOrderAssignee::getDeveloperId, developerId))
                    .stream().map(TicketWorkOrderAssignee::getTicketId).collect(Collectors.toSet());
            if (ticketIdsByDev.isEmpty()) {
                return emptyPage(page, pageSize);
            }
        }

        LambdaQueryWrapper<TicketWorkOrder> qw = new LambdaQueryWrapper<TicketWorkOrder>()
                .eq(companyId != null, TicketWorkOrder::getCompanyId, companyId)
                .in(companyId == null && visible != null, TicketWorkOrder::getCompanyId, visible)
                .like(StringUtils.hasText(title), TicketWorkOrder::getTitle, title)
                .eq(StringUtils.hasText(type), TicketWorkOrder::getType, type)
                .eq(StringUtils.hasText(status), TicketWorkOrder::getStatus, status)
                .eq(StringUtils.hasText(urgency), TicketWorkOrder::getUrgency, urgency)
                .eq(cycleId != null, TicketWorkOrder::getCycleId, cycleId)
                .eq(submitterId != null, TicketWorkOrder::getSubmitterId, submitterId)
                .eq(projectId != null, TicketWorkOrder::getProjectId, projectId)
                .in(ticketIdsByDev != null, TicketWorkOrder::getId, ticketIdsByDev)
                .orderByDesc(TicketWorkOrder::getCreateTime);

        Page<TicketWorkOrder> raw = workOrderMapper.selectPage(new Page<>(page, pageSize), qw);
        Page<TicketVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<TicketVO> vos = raw.getRecords().stream().map(o -> toVo(o, false)).collect(Collectors.toList());
        fillAssigneesAndCycles(vos);
        replyService.fillReplySummaries(vos);
        result.setRecords(vos);
        return result;
    }

    @Override
    public TicketVO detail(Long id) {
        TicketWorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(404, "工单不存在");
        }
        if (!companyGuard.canSeeCompany(order.getCompanyId())) {
            throw new BusinessException(403, "无权查看该工单");
        }
        long loginId = companyGuard.loginId();
        boolean isSubmitter = Objects.equals(order.getSubmitterId(), loginId);
        boolean isHandler = cn.dev33.satoken.stp.StpUtil.hasPermission("ticket:manage:list");
        boolean isAssignee = accessHelper.isAssignee(order.getId(), loginId);
        if (!isSubmitter && !isHandler && !isAssignee) {
            throw new BusinessException(403, "无权查看该工单");
        }
        TicketVO vo = toVo(order, true);
        fillAssigneesAndCycles(List.of(vo));
        replyService.fillReplySummaries(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO update(Long id, TicketUpdateRequest request) {
        TicketWorkOrder order = requireWritableOrder(id);
        if (request != null && !Boolean.TRUE.equals(request.getClearProject()) && request.getProjectId() != null) {
            // 未变更时跳过校验，避免关联项目已删除导致仅改标题也失败
            if (!Objects.equals(request.getProjectId(), order.getProjectId())) {
                request.setProjectId(resolveProjectId(order.getCompanyId(), request.getProjectId()));
            }
        }
        TicketAttributeUpdater.apply(order, request);
        workOrderMapper.updateById(order);
        boolean clearDate = request != null && Boolean.TRUE.equals(request.getClearExpectedCompleteDate());
        boolean clearProject = request != null && Boolean.TRUE.equals(request.getClearProject());
        if (clearDate || clearProject) {
            clearNullableFields(order.getId(), clearDate, false, clearProject);
        }
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdate(TicketBatchUpdateRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getIds())) {
            throw new BusinessException(400, "请选择工单");
        }
        List<TicketWorkOrder> orders = workOrderMapper.selectBatchIds(request.getIds());
        if (orders.size() != request.getIds().size()) {
            throw new BusinessException(404, "部分工单不存在");
        }
        boolean clearDate = Boolean.TRUE.equals(request.getClearExpectedCompleteDate());
        for (TicketWorkOrder order : orders) {
            companyGuard.assertCompanyWritable(order.getCompanyId());
            TicketUpdateRequest one = new TicketUpdateRequest();
            one.setType(request.getType());
            one.setUrgency(request.getUrgency());
            one.setStatus(request.getStatus());
            one.setExpectedCompleteDate(request.getExpectedCompleteDate());
            one.setClearExpectedCompleteDate(request.getClearExpectedCompleteDate());
            TicketAttributeUpdater.apply(order, one);
            workOrderMapper.updateById(order);
            if (clearDate) {
                clearNullableFields(order.getId(), true, false, false);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TicketWorkOrder order = requireWritableOrder(id);
        workOrderMapper.deleteById(order.getId());
        cascadeClean(List.of(order.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            throw new BusinessException(400, "请选择工单");
        }
        List<TicketWorkOrder> orders = workOrderMapper.selectBatchIds(ids);
        if (orders.size() != ids.size()) {
            throw new BusinessException(404, "部分工单不存在");
        }
        for (TicketWorkOrder order : orders) {
            companyGuard.assertCompanyWritable(order.getCompanyId());
            workOrderMapper.deleteById(order.getId());
        }
        cascadeClean(ids);
    }

    @Override
    public Map<String, Object> mySubmissions(long page, long pageSize, Long companyId, Long projectId) {
        long loginId = companyGuard.loginId();
        Set<Long> visible = companyGuard.visibleCompanies();
        if (visible != null && visible.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("stats", Map.of("total", 0, "completed", 0, "rate", 0, "unreadReplyTicketCount", 0));
            empty.put("page", Map.of("list", List.of(), "total", 0, "page", page, "pageSize", pageSize));
            return empty;
        }
        if (companyId != null && visible != null && !visible.contains(companyId)) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("stats", Map.of("total", 0, "completed", 0, "rate", 0, "unreadReplyTicketCount", 0));
            empty.put("page", Map.of("list", List.of(), "total", 0, "page", page, "pageSize", pageSize));
            return empty;
        }

        Set<Long> assignedIds = accessHelper.assignedTicketIds(loginId);
        LambdaQueryWrapper<TicketWorkOrder> countQw = myTicketsQw(loginId, visible, companyId, projectId, assignedIds);
        long total = workOrderMapper.selectCount(countQw);
        long completed = workOrderMapper.selectCount(myTicketsQw(loginId, visible, companyId, projectId, assignedIds)
                .in(TicketWorkOrder::getStatus, List.of("completed", "closed")));
        int rate = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);

        Page<TicketWorkOrder> raw = workOrderMapper.selectPage(new Page<>(page, pageSize),
                myTicketsQw(loginId, visible, companyId, projectId, assignedIds).orderByDesc(TicketWorkOrder::getCreateTime));
        List<TicketVO> vos = raw.getRecords().stream().map(o -> toVo(o, false)).collect(Collectors.toList());
        fillAssigneesAndCycles(vos);
        replyService.fillReplySummaries(vos);

        List<TicketWorkOrder> allMine = workOrderMapper.selectList(myTicketsQw(loginId, visible, companyId, projectId, assignedIds));
        List<TicketVO> allVos = allMine.stream().map(o -> {
            TicketVO v = new TicketVO();
            v.setId(o.getId());
            v.setSubmitterId(o.getSubmitterId());
            return v;
        }).collect(Collectors.toList());
        replyService.fillReplySummaries(allVos);
        long unreadTicketCount = allVos.stream()
                .filter(v -> v.getReplySummary() != null && v.getReplySummary().isHasUnreadReply())
                .count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("completed", completed);
        stats.put("rate", rate);
        stats.put("unreadReplyTicketCount", unreadTicketCount);

        Map<String, Object> pageMap = new HashMap<>();
        pageMap.put("list", vos);
        pageMap.put("total", raw.getTotal());
        pageMap.put("page", raw.getCurrent());
        pageMap.put("pageSize", raw.getSize());

        Map<String, Object> result = new HashMap<>();
        result.put("stats", stats);
        result.put("page", pageMap);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO updateProgress(Long id, TicketProgressRequest request) {
        if (request == null || request.getProgress() == null) {
            throw new BusinessException(400, "进度不能为空");
        }
        TicketWorkOrder order = requireProgressWritable(id);
        int progress = TicketValidation.clampProgress(request.getProgress());
        order.setProgress(progress);
        if (StringUtils.hasText(request.getStatus())) {
            order.setStatus(TicketValidation.requireStatus(request.getStatus()));
        } else {
            TicketAttributeUpdater.deriveStatusFromProgress(order, progress);
        }
        TicketAttributeUpdater.markCompletedAtIfNeeded(order);
        workOrderMapper.updateById(order);
        writeProgressLog(order, null);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO assign(Long id, TicketAssignRequest request) {
        if (request == null) {
            throw new BusinessException(400, "参数不能为空");
        }
        TicketWorkOrder order = requireWritableOrder(id);

        if (request.getDeveloperIds() != null) {
            replaceAssignees(order, request.getDeveloperIds());
        }
        boolean clearCycle = Boolean.TRUE.equals(request.getClearCycle());
        if (clearCycle) {
            order.setCycleId(null);
        } else if (request.getCycleId() != null) {
            TicketDevCycle cycle = cycleMapper.selectById(request.getCycleId());
            if (cycle == null || !Objects.equals(cycle.getCompanyId(), order.getCompanyId())) {
                throw new BusinessException(400, "开发周期不存在或不属于该公司");
            }
            order.setCycleId(cycle.getId());
        }
        if (StringUtils.hasText(request.getStatus())) {
            order.setStatus(TicketValidation.requireStatus(request.getStatus()));
            TicketAttributeUpdater.markCompletedAtIfNeeded(order);
        }
        workOrderMapper.updateById(order);
        if (clearCycle) {
            clearNullableFields(order.getId(), false, true, false);
        }
        return detail(id);
    }

    /** MP 默认 NOT_NULL 策略下 updateById / set(null) 可能写不出 null，用 setSql 清空 */
    private void clearNullableFields(Long ticketId, boolean clearExpectedDate, boolean clearCycle, boolean clearProject) {
        if (!clearExpectedDate && !clearCycle && !clearProject) {
            return;
        }
        List<String> sets = new ArrayList<>();
        if (clearExpectedDate) {
            sets.add("expected_complete_date = NULL");
        }
        if (clearCycle) {
            sets.add("cycle_id = NULL");
        }
        if (clearProject) {
            sets.add("project_id = NULL");
        }
        workOrderMapper.update(null, new LambdaUpdateWrapper<TicketWorkOrder>()
                .eq(TicketWorkOrder::getId, ticketId)
                .setSql(String.join(", ", sets)));
    }

    private void replaceAssignees(TicketWorkOrder order, List<Long> developerIds) {
        assigneeMapper.delete(new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                .eq(TicketWorkOrderAssignee::getTicketId, order.getId()));
        if (CollectionUtils.isEmpty(developerIds)) {
            return;
        }
        List<TicketDeveloper> developers = developerMapper.selectBatchIds(developerIds);
        if (developers.size() != new HashSet<>(developerIds).size()) {
            throw new BusinessException(400, "部分开发人员不存在");
        }
        for (TicketDeveloper d : developers) {
            if (!Objects.equals(d.getCompanyId(), order.getCompanyId())) {
                throw new BusinessException(400, "开发人员必须属于同一公司");
            }
            if (d.getStatus() == null || d.getStatus() != 1) {
                throw new BusinessException(400, "开发人员已禁用：" + d.getName());
            }
            TicketWorkOrderAssignee a = new TicketWorkOrderAssignee();
            a.setTicketId(order.getId());
            a.setDeveloperId(d.getId());
            a.setCreateTime(LocalDateTime.now());
            assigneeMapper.insert(a);
        }
    }

    private void writeProgressLog(TicketWorkOrder order, String remark) {
        TicketProgressLog log = new TicketProgressLog();
        log.setTicketId(order.getId());
        log.setProgress(order.getProgress());
        log.setStatus(order.getStatus());
        log.setOperatorId(StpUtil.getLoginIdAsLong());
        log.setRemark(remark);
        log.setCreateTime(LocalDateTime.now());
        progressLogMapper.insert(log);
    }

    private void cascadeClean(List<Long> ticketIds) {
        if (CollectionUtils.isEmpty(ticketIds)) {
            return;
        }
        assigneeMapper.delete(new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                .in(TicketWorkOrderAssignee::getTicketId, ticketIds));
        progressLogMapper.delete(new LambdaQueryWrapper<TicketProgressLog>()
                .in(TicketProgressLog::getTicketId, ticketIds));
        replyService.cascadeDeleteByTicketIds(ticketIds);
    }

    private String nextTicketNo(Long companyId) {
        LocalDate today = LocalDate.now();
        TicketNumberSequence seq = sequenceMapper.selectForUpdate(companyId, today);
        if (seq == null) {
            try {
                sequenceMapper.insertZero(companyId, today);
            } catch (Exception ignored) {
                // concurrent insert
            }
            seq = sequenceMapper.selectForUpdate(companyId, today);
            if (seq == null) {
                throw new BusinessException("生成工单编号失败");
            }
        }
        long next = (seq.getCurrentValue() == null ? 0L : seq.getCurrentValue()) + 1;
        sequenceMapper.updateValue(companyId, today, next);
        return "KK" + companyId + "-" + today.format(DAY) + "-" + String.format("%04d", next);
    }

    private TicketDevCycle findActiveCycle(Long companyId) {
        return cycleMapper.selectOne(new LambdaQueryWrapper<TicketDevCycle>()
                .eq(TicketDevCycle::getCompanyId, companyId)
                .eq(TicketDevCycle::getStatus, "active")
                .last("LIMIT 1"));
    }

    private TicketWorkOrder requireVisibleOrder(Long id) {
        TicketWorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(404, "工单不存在");
        }
        if (!companyGuard.canSeeCompany(order.getCompanyId())) {
            throw new BusinessException(403, "无权查看该工单");
        }
        return order;
    }

    private TicketWorkOrder requireWritableOrder(Long id) {
        TicketWorkOrder order = requireVisibleOrder(id);
        companyGuard.assertCompanyWritable(order.getCompanyId());
        return order;
    }

    /** 进度：管理员可改；被分配人也可改自己的工单 */
    private TicketWorkOrder requireProgressWritable(Long id) {
        TicketWorkOrder order = requireVisibleOrder(id);
        long loginId = companyGuard.loginId();
        boolean manager = StpUtil.hasPermission("ticket:manage:progress")
                || StpUtil.hasPermission("ticket:manage:list");
        boolean assignee = accessHelper.isAssignee(id, loginId);
        if (!manager && !assignee) {
            throw new BusinessException(403, "无权更新该工单进度");
        }
        if (manager) {
            companyGuard.assertCompanyWritable(order.getCompanyId());
        }
        return order;
    }

    private TicketVO toVo(TicketWorkOrder order, boolean withDescription) {
        TicketVO vo = new TicketVO();
        vo.setId(order.getId());
        vo.setCompanyId(order.getCompanyId());
        vo.setProjectId(order.getProjectId());
        vo.setTicketNo(order.getTicketNo());
        vo.setTitle(order.getTitle());
        vo.setType(order.getType());
        vo.setUrgency(order.getUrgency());
        if (withDescription) {
            vo.setDescription(order.getDescription());
        }
        vo.setStatus(order.getStatus());
        vo.setProgress(order.getProgress());
        vo.setExpectedCompleteDate(order.getExpectedCompleteDate());
        vo.setCycleId(order.getCycleId());
        vo.setSubmitterId(order.getSubmitterId());
        vo.setSubmitterName(order.getSubmitterName());
        vo.setCompletedAt(order.getCompletedAt());
        vo.setCreateTime(order.getCreateTime());
        vo.setUpdateTime(order.getUpdateTime());
        if (order.getCompanyId() != null) {
            SysDept dept = deptService.getById(order.getCompanyId());
            if (dept != null) {
                vo.setCompanyName(dept.getName());
            }
        }
        return vo;
    }

    private void fillAssigneesAndCycles(List<TicketVO> vos) {
        if (CollectionUtils.isEmpty(vos)) {
            return;
        }
        Set<Long> ticketIds = vos.stream().map(TicketVO::getId).collect(Collectors.toSet());
        Set<Long> cycleIds = vos.stream().map(TicketVO::getCycleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> projectIds = vos.stream().map(TicketVO::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());

        List<TicketWorkOrderAssignee> assignees = assigneeMapper.selectList(
                new LambdaQueryWrapper<TicketWorkOrderAssignee>().in(TicketWorkOrderAssignee::getTicketId, ticketIds));
        Set<Long> developerIds = assignees.stream().map(TicketWorkOrderAssignee::getDeveloperId).collect(Collectors.toSet());
        Map<Long, TicketDeveloper> developerMap = developerIds.isEmpty() ? Map.of()
                : developerMapper.selectBatchIds(developerIds).stream()
                .collect(Collectors.toMap(TicketDeveloper::getId, d -> d, (a, b) -> a));

        Map<Long, List<TicketWorkOrderAssignee>> byTicket = assignees.stream()
                .collect(Collectors.groupingBy(TicketWorkOrderAssignee::getTicketId));

        Map<Long, TicketDevCycle> cycleMap = cycleIds.isEmpty() ? Map.of()
                : cycleMapper.selectBatchIds(cycleIds).stream()
                .collect(Collectors.toMap(TicketDevCycle::getId, c -> c, (a, b) -> a));

        Map<Long, String> projectNameMap = projectIds.isEmpty() ? Map.of()
                : projectService.listByIds(projectIds).stream()
                .collect(Collectors.toMap(PmProject::getId, PmProject::getName, (a, b) -> a));

        for (TicketVO vo : vos) {
            List<TicketWorkOrderAssignee> list = byTicket.getOrDefault(vo.getId(), List.of());
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (TicketWorkOrderAssignee a : list) {
                ids.add(a.getDeveloperId());
                TicketDeveloper d = developerMap.get(a.getDeveloperId());
                if (d != null) {
                    names.add(d.getName());
                }
            }
            vo.setDeveloperIds(ids);
            vo.setDeveloperNames(String.join("、", names));
            if (vo.getCycleId() != null) {
                TicketDevCycle cycle = cycleMap.get(vo.getCycleId());
                if (cycle != null) {
                    vo.setCycleName(cycle.getName());
                }
            }
            if (vo.getProjectId() != null) {
                vo.setProjectName(projectNameMap.get(vo.getProjectId()));
            }
        }

        long loginId = companyGuard.loginId();
        boolean manager = StpUtil.hasPermission("ticket:manage:progress")
                || StpUtil.hasPermission("ticket:manage:list");
        Set<Long> assignedTicketIds = manager ? Set.of() : accessHelper.assignedTicketIds(loginId);
        for (TicketVO vo : vos) {
            vo.setCanUpdateProgress(manager || assignedTicketIds.contains(vo.getId()));
        }
    }

    private Long resolveProjectId(Long companyId, Long projectId) {
        if (projectId == null) {
            return null;
        }
        PmProject project = projectService.getById(projectId);
        if (project == null) {
            throw new BusinessException(400, "关联项目不存在");
        }
        if (!Objects.equals(project.getCompanyId(), companyId)) {
            throw new BusinessException(400, "关联项目必须属于所选公司");
        }
        return project.getId();
    }

    private LambdaQueryWrapper<TicketWorkOrder> myTicketsQw(long loginId, Set<Long> visible,
                                                            Long companyId, Long projectId,
                                                            Set<Long> assignedIds) {
        Set<Long> assigned = assignedIds == null ? Set.of() : assignedIds;
        LambdaQueryWrapper<TicketWorkOrder> qw = new LambdaQueryWrapper<TicketWorkOrder>()
                .eq(companyId != null, TicketWorkOrder::getCompanyId, companyId)
                .in(companyId == null && visible != null, TicketWorkOrder::getCompanyId, visible)
                .eq(projectId != null, TicketWorkOrder::getProjectId, projectId);
        qw.and(w -> {
            w.eq(TicketWorkOrder::getSubmitterId, loginId);
            if (!assigned.isEmpty()) {
                w.or().in(TicketWorkOrder::getId, assigned);
            }
        });
        return qw;
    }

    private Page<TicketVO> emptyPage(long page, long pageSize) {
        Page<TicketVO> p = new Page<>(page, pageSize, 0);
        p.setRecords(Collections.emptyList());
        return p;
    }
}
