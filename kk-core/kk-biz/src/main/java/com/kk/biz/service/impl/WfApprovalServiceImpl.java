package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.ApprovalQuery;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.RollbackRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinMonthVerify;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.entity.WfApprovalFlow;
import com.kk.biz.entity.WfApprovalLog;
import com.kk.biz.entity.WfApprovalTask;
import com.kk.biz.entity.WfRollback;
import com.kk.biz.mapper.FinLedgerMapper;
import com.kk.biz.mapper.FinMonthVerifyMapper;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.mapper.WfApprovalLogMapper;
import com.kk.biz.mapper.WfApprovalMapper;
import com.kk.biz.mapper.WfApprovalTaskMapper;
import com.kk.biz.mapper.WfRollbackMapper;
import com.kk.biz.service.FinPayChannelService;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.service.WfApprovalFlowService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.support.BizNoGenerator;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysNotificationService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WfApprovalServiceImpl extends ServiceImpl<WfApprovalMapper, WfApproval> implements WfApprovalService {

    private static final String ROLE_FINANCE = "finance";

    private final WfApprovalTaskMapper taskMapper;
    private final WfApprovalLogMapper logMapper;
    private final WfRollbackMapper rollbackMapper;
    private final SysUserService userService;
    private final FinProjectAccountService projectAccountService;
    private final FinanceService financeService;
    private final HrWalletService walletService;
    private final FinPayChannelService payChannelService;
    private final FinMonthVerifyMapper monthVerifyMapper;
    private final FinPoolMapper poolMapper;
    private final FinLedgerMapper ledgerMapper;
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final SysFileService fileService;
    private final BizNoGenerator bizNoGenerator;
    private final PlatformTransactionManager transactionManager;
    private final SysNotificationService notificationService;
    private final WfApprovalFlowService approvalFlowService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WfApproval submit(ApprovalSubmitRequest request) {
        long applicantId = StpUtil.getLoginIdAsLong();
        String type = request.getType();
        if (!StringUtils.hasText(type)) {
            throw new BusinessException("审批类型不能为空");
        }
        validateSubmit(request, applicantId);

        WfApprovalFlow flow = approvalFlowService.requireEnabled(type);
        List<Long> assignees = approvalFlowService.resolveAssigneeIds(flow);
        if (assignees.isEmpty()) {
            throw new BusinessException("未找到审批人，请先在「审批配置」中设置角色或指定人员");
        }

        WfApproval approval = new WfApproval();
        approval.setBizNo(bizNoGenerator.approval());
        approval.setType(type);
        approval.setTitle(StringUtils.hasText(request.getTitle())
                ? request.getTitle()
                : ApprovalTypes.label(type));
        approval.setStatus("PENDING");
        approval.setApplicantId(applicantId);
        approval.setAmount(request.getAmount());
        approval.setProjectId(request.getProjectId());
        approval.setPoolId(request.getPoolId());
        approval.setRemark(request.getRemark());
        approval.setPayload(request.getPayload() == null ? "{}" : JSONUtil.toJsonStr(request.getPayload()));
        String passMode = StringUtils.hasText(flow.getPassMode()) ? flow.getPassMode().toUpperCase() : "ALL";
        approval.setPassMode(passMode);
        int timeoutHours = flow.getTimeoutHours() == null ? 0 : flow.getTimeoutHours();
        approval.setAutoPass(timeoutHours > 0 ? 1 : 0);
        if (timeoutHours > 0) {
            approval.setTimeoutAt(LocalDateTime.now().plusHours(timeoutHours));
        }
        approval.setConfirmStatus(0);
        save(approval);

        if (request.getVoucherFileIds() != null && !request.getVoucherFileIds().isEmpty()) {
            fileService.bindBiz(request.getVoucherFileIds(), "approval", approval.getId());
        }

        for (Long uid : assignees) {
            WfApprovalTask task = new WfApprovalTask();
            task.setApprovalId(approval.getId());
            task.setAssigneeId(uid);
            task.setAction("PENDING");
            taskMapper.insert(task);
        }
        addLog(approval.getId(), applicantId, "SUBMIT", "发起审批");
        String applicantName = userDisplayName(applicantId);
        notificationService.notifyUsers(
                assignees.stream().filter(uid -> !Objects.equals(uid, applicantId)).toList(),
                "待审批 · " + approval.getTitle(),
                applicantName + " 发起了「" + ApprovalTypes.label(type) + "」，单号 " + approval.getBizNo() + "，请尽快处理",
                "approval", approval.getId(), "/workflow/center");
        return detail(approval.getId());
    }

    @Override
    public Page<WfApproval> page(ApprovalQuery query) {
        if (query == null) {
            query = new ApprovalQuery();
        }
        long loginId = StpUtil.getLoginIdAsLong();
        String scope = StringUtils.hasText(query.getScope()) ? query.getScope() : "all";
        LambdaQueryWrapper<WfApproval> wrapper = new LambdaQueryWrapper<WfApproval>()
                .eq(StringUtils.hasText(query.getType()), WfApproval::getType, query.getType())
                .eq(StringUtils.hasText(query.getStatus()), WfApproval::getStatus, query.getStatus())
                .eq(query.getProjectId() != null, WfApproval::getProjectId, query.getProjectId())
                .eq(query.getPoolId() != null, WfApproval::getPoolId, query.getPoolId())
                .ge(query.getStartTime() != null, WfApproval::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, WfApproval::getCreateTime, query.getEndTime())
                .orderByDesc(WfApproval::getId);
        if (query.getMinAmount() != null) {
            wrapper.ge(WfApproval::getAmount, query.getMinAmount());
        }
        if (query.getMaxAmount() != null) {
            wrapper.le(WfApproval::getAmount, query.getMaxAmount());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(WfApproval::getBizNo, kw)
                    .or().like(WfApproval::getTitle, kw)
                    .or().like(WfApproval::getRemark, kw));
        }

        if ("mine".equals(scope)) {
            wrapper.eq(WfApproval::getApplicantId, loginId);
        } else if ("todo".equals(scope)) {
            List<Long> ids = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                            .eq(WfApprovalTask::getAssigneeId, loginId)
                            .eq(WfApprovalTask::getAction, "PENDING"))
                    .stream().map(WfApprovalTask::getApprovalId).distinct().toList();
            if (ids.isEmpty()) {
                return new Page<>(query.getPage(), query.getPageSize());
            }
            wrapper.in(WfApproval::getId, ids).eq(WfApproval::getStatus, "PENDING");
        }

        Page<WfApproval> result = page(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        fillExtras(result.getRecords());
        return result;
    }

    @Override
    public WfApproval detail(Long id) {
        WfApproval approval = getById(id);
        if (approval == null) {
            throw new BusinessException("审批单不存在");
        }
        fillExtras(List.of(approval));
        List<WfApprovalTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                .eq(WfApprovalTask::getApprovalId, id)
                .orderByAsc(WfApprovalTask::getId));
        fillTaskNames(tasks);
        approval.setTasks(tasks);
        List<WfApprovalLog> logs = logMapper.selectList(new LambdaQueryWrapper<WfApprovalLog>()
                .eq(WfApprovalLog::getApprovalId, id)
                .orderByAsc(WfApprovalLog::getId));
        fillLogNames(logs);
        approval.setLogs(logs);
        fillFlags(approval);
        approval.setPayloadData(buildPayloadData(approval));
        approval.setVoucherFiles(fileService.listByBiz("approval", id));
        approval.setReceiptFiles(fileService.listByBiz("approval_receipt", id));
        return approval;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        long loginId = StpUtil.getLoginIdAsLong();
        WfApproval approval = requirePending(id);
        WfApprovalTask task = requireMyPendingTask(id, loginId);
        task.setAction("APPROVE");
        task.setComment(comment);
        task.setActTime(LocalDateTime.now());
        taskMapper.updateById(task);
        addLog(id, loginId, "APPROVE", comment);

        // 兼容旧单：未写入 passMode 时按会签处理
        boolean pass;
        String mode = StringUtils.hasText(approval.getPassMode()) ? approval.getPassMode() : "ALL";
        if ("ANY".equalsIgnoreCase(mode)) {
            // 或签：一人通过即可，其余待办跳过
            skipPendingTasks(id, "或签已通过，自动跳过");
            pass = true;
        } else {
            pass = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                            .eq(WfApprovalTask::getApprovalId, id)).stream()
                    .allMatch(t -> "APPROVE".equals(t.getAction()) || "SKIP".equals(t.getAction()));
        }
        if (pass) {
            onApproved(approval, false);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        long loginId = StpUtil.getLoginIdAsLong();
        WfApproval approval = requirePending(id);
        WfApprovalTask task = requireMyPendingTask(id, loginId);
        task.setAction("REJECT");
        task.setComment(comment);
        task.setActTime(LocalDateTime.now());
        taskMapper.updateById(task);

        approval.setStatus("REJECTED");
        updateById(approval);
        closePendingTasks(id, "审批已拒绝");
        addLog(id, loginId, "REJECT", comment);
        notificationService.notifyUser(
                approval.getApplicantId(),
                "审批已拒绝 · " + approval.getTitle(),
                "单号 " + approval.getBizNo() + " 已被拒绝"
                        + (StringUtils.hasText(comment) ? "：" + comment : ""),
                "approval", approval.getId(), "/workflow/center");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        long loginId = StpUtil.getLoginIdAsLong();
        WfApproval approval = getById(id);
        if (approval == null) {
            throw new BusinessException("审批单不存在");
        }
        if (!Objects.equals(approval.getApplicantId(), loginId) && !StpUtil.hasRole("admin")) {
            throw new BusinessException("只能撤回自己发起的审批");
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new BusinessException("仅待审批状态可撤回");
        }
        approval.setStatus("WITHDRAWN");
        updateById(approval);
        closePendingTasks(id, "审批已撤回");
        addLog(id, loginId, "WITHDRAW", "申请人撤回");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void uploadReceipt(Long id, List<Long> fileIds) {
        long loginId = StpUtil.getLoginIdAsLong();
        WfApproval approval = getById(id);
        if (approval == null) {
            throw new BusinessException("审批单不存在");
        }
        if (!List.of("APPROVED", "TIMEOUT_PASS").contains(approval.getStatus())) {
            throw new BusinessException("审批通过后才能上传回执");
        }
        if (approval.getConfirmStatus() == null || approval.getConfirmStatus() != 1) {
            throw new BusinessException("当前状态无需上传回执");
        }
        if (!canFinanceHandle(loginId)) {
            throw new BusinessException("仅财务可上传回执");
        }
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException("请上传回执文件");
        }
        fileService.bindBiz(fileIds, "approval_receipt", id);
        approval.setReceiptFileIds(fileIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        approval.setConfirmStatus(2);
        updateById(approval);
        addLog(id, loginId, "RECEIPT", "财务上传回执");
        notificationService.notifyUser(
                approval.getApplicantId(),
                "请确认到账 · " + approval.getTitle(),
                "财务已上传回执，单号 " + approval.getBizNo() + "，请到审批中心确认到账",
                "approval", approval.getId(), "/workflow/center");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceived(Long id) {
        long loginId = StpUtil.getLoginIdAsLong();
        WfApproval approval = getById(id);
        if (approval == null) {
            throw new BusinessException("审批单不存在");
        }
        if (!Objects.equals(approval.getApplicantId(), loginId) && !StpUtil.hasRole("admin")) {
            throw new BusinessException("仅申请人可确认到账");
        }
        if (approval.getConfirmStatus() == null || approval.getConfirmStatus() != 2) {
            throw new BusinessException("请等待财务上传回执后再确认");
        }
        approval.setConfirmStatus(3);
        updateById(approval);
        addLog(id, loginId, "CONFIRM", "申请人确认到账");
        executeMoneyEffect(approval);
        List<Long> financeIds = userService.listUserIdsByRoleCode(ROLE_FINANCE);
        if (financeIds.isEmpty()) {
            financeIds = userService.listUserIdsByRoleCode("admin");
        }
        notificationService.notifyUsers(
                financeIds.stream().filter(uid -> !Objects.equals(uid, loginId)).toList(),
                "已确认到账 · " + approval.getTitle(),
                "申请人已确认，单号 " + approval.getBizNo() + "，资金已入账",
                "approval", approval.getId(), "/workflow/center");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WfApproval submitRollback(RollbackRequest request) {
        WfApproval origin = getById(request.getApprovalId());
        if (origin == null) {
            throw new BusinessException("原审批单不存在");
        }
        if (!List.of("APPROVED", "TIMEOUT_PASS").contains(origin.getStatus())) {
            throw new BusinessException("仅已通过的审批可发起回退");
        }
        if (!ApprovalTypes.canMoneyRollback(origin.getType())) {
            throw new BusinessException("该审批未涉及资金动账（如资金配置），不能发起资金回退");
        }
        if (origin.getAmount() == null || origin.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("原审批没有可回退金额");
        }
        if (ApprovalTypes.needMoneyConfirm(origin.getType())
                && (origin.getConfirmStatus() == null || origin.getConfirmStatus() != 3)) {
            throw new BusinessException("原审批尚未完成到账确认，无法回退");
        }
        BigDecimal amount = request.getAmount();
        if ("FULL".equals(request.getMode())) {
            amount = origin.getAmount() == null ? BigDecimal.ZERO : origin.getAmount();
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("回退金额必须大于 0");
        }
        if (origin.getAmount() != null && amount.compareTo(origin.getAmount()) > 0) {
            throw new BusinessException("回退金额不能超过原审批金额");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("originApprovalId", origin.getId());
        payload.put("originBizNo", origin.getBizNo());
        payload.put("mode", request.getMode());
        payload.put("amount", amount);

        ApprovalSubmitRequest submit = new ApprovalSubmitRequest();
        submit.setType(ApprovalTypes.ROLLBACK);
        submit.setTitle("回退 · " + origin.getBizNo());
        submit.setAmount(amount);
        submit.setProjectId(origin.getProjectId());
        submit.setPoolId(origin.getPoolId());
        submit.setRemark(request.getReason());
        submit.setPayload(payload);
        WfApproval rollbackApproval = submit(submit);

        WfRollback rb = new WfRollback();
        rb.setBizNo(bizNoGenerator.rollback());
        rb.setApprovalId(origin.getId());
        rb.setRollbackApprovalId(rollbackApproval.getId());
        rb.setMode(request.getMode());
        rb.setAmount(amount);
        rb.setStatus("PENDING");
        rb.setReason(request.getReason());
        rollbackMapper.insert(rb);

        origin.setStatus("ROLLING");
        updateById(origin);
        return rollbackApproval;
    }

    @Override
    public int autoPassTimeout() {
        List<WfApproval> list = list(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getStatus, "PENDING")
                .eq(WfApproval::getAutoPass, 1)
                .le(WfApproval::getTimeoutAt, LocalDateTime.now()));
        int count = 0;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (WfApproval approval : list) {
            try {
                tx.executeWithoutResult(status -> {
                    WfApproval fresh = getById(approval.getId());
                    if (fresh == null || !"PENDING".equals(fresh.getStatus())) {
                        return;
                    }
                    List<WfApprovalTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                            .eq(WfApprovalTask::getApprovalId, fresh.getId())
                            .eq(WfApprovalTask::getAction, "PENDING"));
                    for (WfApprovalTask task : tasks) {
                        task.setAction("SKIP");
                        task.setComment("超时未操作，自动通过");
                        task.setActTime(LocalDateTime.now());
                        taskMapper.updateById(task);
                    }
                    addLog(fresh.getId(), null, "TIMEOUT_PASS", "超过3天未操作，自动通过");
                    onApproved(fresh, true);
                });
                count++;
            } catch (Exception e) {
                log.warn("审批超时自动通过失败 id={}, err={}", approval.getId(), e.getMessage());
            }
        }
        return count;
    }

    private void onApproved(WfApproval approval, boolean timeout) {
        approval.setStatus(timeout ? "TIMEOUT_PASS" : "APPROVED");
        approval.setPassTime(LocalDateTime.now());
        updateById(approval);

        String passLabel = timeout ? "超时自动通过" : "已通过";
        if (ApprovalTypes.needMoneyConfirm(approval.getType())) {
            // 等财务回执 + 申请人确认后再动账
            approval.setConfirmStatus(1);
            updateById(approval);
            notificationService.notifyUser(
                    approval.getApplicantId(),
                    passLabel + " · " + approval.getTitle(),
                    "单号 " + approval.getBizNo() + " 已通过，等待财务上传回执后请确认到账",
                    "approval", approval.getId(), "/workflow/center");
            List<Long> financeIds = userService.listUserIdsByRoleCode(ROLE_FINANCE);
            if (financeIds.isEmpty()) {
                financeIds = userService.listUserIdsByRoleCode("admin");
            }
            notificationService.notifyUsers(
                    financeIds.stream().filter(uid -> !Objects.equals(uid, approval.getApplicantId())).toList(),
                    "待上传回执 · " + approval.getTitle(),
                    "单号 " + approval.getBizNo() + " 已审批通过，请上传财务回执",
                    "approval", approval.getId(), "/workflow/center");
            return;
        }
        executeMoneyEffect(approval);
        notificationService.notifyUser(
                approval.getApplicantId(),
                passLabel + " · " + approval.getTitle(),
                "单号 " + approval.getBizNo() + " 已通过并生效",
                "approval", approval.getId(), "/workflow/center");
    }

    private String userDisplayName(Long userId) {
        if (userId == null) {
            return "用户";
        }
        SysUser u = userService.getById(userId);
        if (u == null) {
            return "用户" + userId;
        }
        return StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
    }

    private void executeMoneyEffect(WfApproval approval) {
        String type = approval.getType();
        JSONObject payload = JSONUtil.parseObj(approval.getPayload());
        switch (type) {
            case ApprovalTypes.PROJECT_CREATE -> effectProjectCreate(approval, payload);
            case ApprovalTypes.PROJECT_DELETE -> effectProjectDelete(approval);
            case ApprovalTypes.PROJECT_ADVANCE -> {
                projectAccountService.advanceFromCompany(
                        approval.getProjectId(),
                        approval.getPoolId(),
                        approval.getAmount(),
                        approval.getId(),
                        approval.getRemark());
                syncReserveAfterFundChange(approval.getProjectId());
            }
            case ApprovalTypes.REIMBURSE_PROJECT, ApprovalTypes.SALARY_APPLY -> {
                assertExpenseWithinQuota(approval.getProjectId(), approval.getAmount());
                projectAccountService.expense(
                        approval.getProjectId(),
                        approval.getAmount(),
                        approval.getId(),
                        ApprovalTypes.label(type),
                        approval.getRemark());
            }
            case ApprovalTypes.REIMBURSE_PERSONAL -> effectPersonalReimburse(approval);
            case ApprovalTypes.SHARE_CONFIG -> effectShareConfig(approval, payload);
            case ApprovalTypes.PROJECT_SETTLE -> {
                assertSettleWithinQuota(approval.getProjectId(), approval.getAmount());
                effectSettle(approval, payload);
            }
            case ApprovalTypes.RESERVE_RETURN -> projectAccountService.returnReserveToCompany(
                    approval.getProjectId(), approval.getPoolId(), approval.getId(), approval.getRemark());
            case ApprovalTypes.LEDGER_REGISTER -> effectLedgerRegister(approval, payload);
            case ApprovalTypes.MONTHLY_VERIFY -> effectMonthlyVerify(approval, payload);
            case ApprovalTypes.ROLLBACK -> effectRollback(approval, payload);
            default -> {
            }
        }
        addLog(approval.getId(), null, "EFFECT", "审批生效，已执行业务动账");
    }

    private void effectProjectCreate(WfApproval approval, JSONObject payload) {
        PmProject project = new PmProject();
        project.setName(payload.getStr("name"));
        project.setCode(payload.getStr("code"));
        project.setOwnerId(payload.getLong("ownerId", approval.getApplicantId()));
        project.setPoolId(payload.getLong("poolId"));
        project.setBudget(payload.getBigDecimal("budget", BigDecimal.ZERO));
        project.setReserveAmount(payload.getBigDecimal("reserveAmount", BigDecimal.ZERO));
        project.setSettledAmount(BigDecimal.ZERO);
        project.setStatus(payload.getInt("status", 1));
        project.setApproveStatus(1);
        project.setDescription(payload.getStr("description"));
        if (StringUtils.hasText(payload.getStr("startDate"))) {
            project.setStartDate(java.time.LocalDate.parse(payload.getStr("startDate")));
        }
        if (StringUtils.hasText(payload.getStr("endDate"))) {
            project.setEndDate(java.time.LocalDate.parse(payload.getStr("endDate")));
        }
        projectMapper.insert(project);
        projectAccountService.getOrCreate(project.getId());
        if (project.getReserveAmount() != null && project.getReserveAmount().compareTo(BigDecimal.ZERO) > 0) {
            var account = projectAccountService.getOrCreate(project.getId());
            account.setReserveAmount(project.getReserveAmount());
            projectAccountService.updateById(account);
        }
        // 分成配置
        if (payload.containsKey("members")) {
            List<PmProjectMember> members = JSONUtil.toList(payload.getJSONArray("members"), PmProjectMember.class);
            saveMembers(project.getId(), members);
        }
        // 回写 projectId 便于查询
        approval.setProjectId(project.getId());
        updateById(approval);
    }

    private void effectProjectDelete(WfApproval approval) {
        if (approval.getProjectId() == null) {
            throw new BusinessException("缺少项目ID");
        }
        projectMapper.deleteById(approval.getProjectId());
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, approval.getProjectId()));
    }

    private void effectPersonalReimburse(WfApproval approval) {
        if (approval.getAmount() == null || approval.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        // 确认到账：公司总账出账 + 个人钱包入账（报销款归属个人）
        FinPool pool = resolvePool(approval.getPoolId());
        BigDecimal amount = approval.getAmount();
        BigDecimal poolBefore = pool.getBalance();
        boolean ok = new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(poolMapper)
                .eq(FinPool::getId, pool.getId())
                .ge(FinPool::getBalance, amount)
                .setSql("balance = balance - " + amount.toPlainString())
                .update();
        if (!ok) {
            throw new BusinessException("公司总账余额不足，无法完成报销动账");
        }
        pool = poolMapper.selectById(pool.getId());
        Long companyLedgerId = writeSimpleLedger("REIMBURSE", "POOL", pool.getId(), null,
                amount.negate(), poolBefore, pool.getBalance(), null, null, approval.getId(),
                "个人报销扣款", approval.getRemark());

        HrWallet walletBefore = walletService.getOrCreate(approval.getApplicantId());
        BigDecimal wb = walletBefore.getBalance();
        HrWallet walletAfter = walletService.changeBalance(approval.getApplicantId(), amount);
        writeSimpleLedger("REIMBURSE", "WALLET", pool.getId(), approval.getApplicantId(),
                amount, wb, walletAfter.getBalance(), null, companyLedgerId, approval.getId(),
                "个人报销入账", approval.getRemark());
    }

    private void effectLedgerRegister(WfApproval approval, JSONObject payload) {
        String bizType = payload.getStr("bizType");
        if (!StringUtils.hasText(bizType)) {
            throw new BusinessException("缺少记账类型");
        }
        LedgerCreateRequest req = new LedgerCreateRequest();
        req.setBizType(bizType);
        req.setAccountType(payload.getStr("accountType", "POOL"));
        req.setPoolId(payload.getLong("poolId", approval.getPoolId()));
        if ("INCOME".equals(bizType)) {
            req.setChannelId(payload.getLong("channelId"));
            req.setFeeMode(payload.getStr("feeMode"));
            req.setFeeValue(payload.getBigDecimal("feeValue"));
        }
        req.setUserId(payload.getLong("userId"));
        req.setProjectId(payload.getLong("projectId", approval.getProjectId()));
        req.setAmount(payload.getBigDecimal("amount", approval.getAmount()));
        req.setTitle(payload.getStr("title", approval.getTitle()));
        req.setRemark(StringUtils.hasText(approval.getRemark()) ? approval.getRemark() : payload.getStr("remark"));
        req.setApprovalId(approval.getId());
        List<Long> voucherIds = new ArrayList<>();
        if (payload.containsKey("voucherFileIds") && payload.get("voucherFileIds") != null) {
            for (Object id : payload.getJSONArray("voucherFileIds")) {
                if (id != null) {
                    voucherIds.add(Long.valueOf(String.valueOf(id)));
                }
            }
        }
        if (!voucherIds.isEmpty()) {
            req.setVoucherFileIds(voucherIds);
        }
        financeService.createLedger(req);
    }

    private void effectMonthlyVerify(WfApproval approval, JSONObject payload) {
        String month = payload.getStr("verifyMonth");
        Long channelId = payload.getLong("channelId");
        if (!StringUtils.hasText(month) || channelId == null) {
            throw new BusinessException("月度核验缺少月份或渠道");
        }
        var channel = payChannelService.getById(channelId);
        if (channel == null) {
            throw new BusinessException("收款渠道不存在");
        }
        FinMonthVerify existing = monthVerifyMapper.selectOne(new LambdaQueryWrapper<FinMonthVerify>()
                .eq(FinMonthVerify::getVerifyMonth, month)
                .eq(FinMonthVerify::getChannelId, channelId)
                .last("LIMIT 1"));
        BigDecimal statement = payload.getBigDecimal("statementBalance");
        BigDecimal systemBal = payload.getBigDecimal("systemBalance", channel.getBalance());
        BigDecimal diff = null;
        if (statement != null && systemBal != null) {
            diff = statement.subtract(systemBal);
        }
        if (existing == null) {
            existing = new FinMonthVerify();
            existing.setVerifyMonth(month);
            existing.setChannelId(channelId);
            existing.setPoolId(channel.getPoolId());
            existing.setSystemBalance(systemBal);
            existing.setStatementBalance(statement);
            existing.setDiffAmount(diff);
            existing.setStatus("PASSED");
            existing.setApprovalId(approval.getId());
            existing.setRemark(approval.getRemark());
            monthVerifyMapper.insert(existing);
        } else {
            existing.setSystemBalance(systemBal);
            existing.setStatementBalance(statement);
            existing.setDiffAmount(diff);
            existing.setStatus("PASSED");
            existing.setApprovalId(approval.getId());
            existing.setRemark(approval.getRemark());
            monthVerifyMapper.updateById(existing);
        }
        List<Long> voucherIds = new ArrayList<>();
        if (payload.containsKey("voucherFileIds") && payload.get("voucherFileIds") != null) {
            for (Object id : payload.getJSONArray("voucherFileIds")) {
                if (id != null) {
                    voucherIds.add(Long.valueOf(String.valueOf(id)));
                }
            }
        }
        if (!voucherIds.isEmpty()) {
            fileService.bindBiz(voucherIds, "month_verify", existing.getId());
        } else {
            var approvalFiles = fileService.listByBiz("approval", approval.getId());
            if (approvalFiles != null && !approvalFiles.isEmpty()) {
                fileService.bindBiz(approvalFiles.stream().map(f -> f.getId()).toList(), "month_verify", existing.getId());
            }
        }
    }

    /** 总账登记回退：按原方向反向记账 */
    private void effectLedgerRegisterRollback(WfApproval approval, WfApproval origin, BigDecimal amount) {
        JSONObject originPayload = JSONUtil.parseObj(origin.getPayload());
        String bizType = originPayload.getStr("bizType");
        if ("INCOME".equals(bizType)) {
            BigDecimal originGross = originPayload.getBigDecimal("amount", origin.getAmount());
            financeService.reverseIncomeRegister(
                    originPayload.getLong("poolId", origin.getPoolId()),
                    originPayload.getLong("channelId"),
                    amount,
                    originGross,
                    originPayload.getStr("feeMode"),
                    originPayload.getBigDecimal("feeValue"),
                    approval.getId(),
                    "回退 · " + origin.getBizNo(),
                    "回退原单 " + origin.getBizNo());
            return;
        }
        LedgerCreateRequest req = new LedgerCreateRequest();
        req.setAmount(amount);
        req.setPoolId(originPayload.getLong("poolId", origin.getPoolId()));
        req.setUserId(originPayload.getLong("userId"));
        req.setProjectId(originPayload.getLong("projectId", origin.getProjectId()));
        req.setApprovalId(approval.getId());
        req.setTitle("回退 · " + origin.getBizNo());
        req.setRemark("回退原单 " + origin.getBizNo());
        if ("EXPENSE".equals(bizType)) {
            req.setBizType("INCOME");
            req.setAccountType("POOL");
        } else if ("TRANSFER".equals(bizType)) {
            req.setBizType("TRANSFER");
            String accountType = originPayload.getStr("accountType", "POOL");
            // 原 POOL→个人 则回退为 个人→POOL
            req.setAccountType("POOL".equals(accountType) ? "WALLET" : "POOL");
            if (req.getUserId() == null) {
                throw new BusinessException("原划拨缺少人员，无法回退");
            }
        } else {
            throw new BusinessException("无法回退的记账类型: " + bizType);
        }
        financeService.createLedger(req);
    }

    private void effectShareConfig(WfApproval approval, JSONObject payload) {
        Long projectId = approval.getProjectId();
        if (projectId == null) {
            throw new BusinessException("缺少项目");
        }
        Long poolId = payload.getLong("poolId", approval.getPoolId());
        BigDecimal budget = payload.getBigDecimal("budget");
        BigDecimal expensePercent = payload.getBigDecimal("expensePercent");
        BigDecimal reservePercent = payload.getBigDecimal("reservePercent");
        BigDecimal settlePercent = payload.getBigDecimal("settlePercent");
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (poolId != null) {
            project.setPoolId(poolId);
        }
        if (budget != null) {
            project.setBudget(budget);
        }
        if (expensePercent != null || reservePercent != null || settlePercent != null) {
            BigDecimal rp = reservePercent != null ? reservePercent : nz(project.getReservePercent());
            BigDecimal sp = settlePercent != null ? settlePercent : nz(project.getSettlePercent());
            // 支出不再占配置比例；分成 + 预留 = 100%，expensePercent 固定写 0
            assertSettleReservePercents(sp, rp);
            project.setExpensePercent(BigDecimal.ZERO);
            project.setReservePercent(rp);
            project.setSettlePercent(sp);
        }
        // 预留规划额度仅写入项目展示字段，不扣减项目余额；结束时用「预留回公司」退回结余
        BigDecimal reservePlan = quotaOf(project, projectId, project.getReservePercent());
        project.setReserveAmount(reservePlan);
        projectMapper.updateById(project);
        if (payload.containsKey("members")) {
            List<PmProjectMember> members = JSONUtil.toList(payload.getJSONArray("members"), PmProjectMember.class);
            saveMembers(projectId, members);
        }
        // 只更新规则与人员，不改已转入 / 已分成 / 已支出
    }

    private void syncReserveAfterFundChange(Long projectId) {
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            return;
        }
        project.setReserveAmount(quotaOf(project, projectId, project.getReservePercent()));
        projectMapper.updateById(project);
    }

    private void assertSettleReservePercents(BigDecimal settlePercent, BigDecimal reservePercent) {
        BigDecimal rp = nz(reservePercent);
        BigDecimal sp = nz(settlePercent);
        if (rp.compareTo(BigDecimal.ZERO) < 0 || sp.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("分成/预留比例不能为负");
        }
        BigDecimal sum = rp.add(sp);
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("分成% + 预留% 必须为 100%，当前 " + sum + "%（支出不占比例，直接从项目结余扣）");
        }
    }

    /** 支出不再校验比例额度，仅校验项目结余（在动账时扣减） */
    private void assertExpenseWithinQuota(Long projectId, BigDecimal addAmount) {
        var account = projectAccountService.getOrCreate(projectId);
        if (nz(addAmount).compareTo(nz(account.getBalance())) > 0) {
            throw new BusinessException("超过项目结余 ¥" + nz(account.getBalance()).toPlainString());
        }
    }

    /** 预算或已预支作为比例计算基数 */
    private BigDecimal fundBase(PmProject project, Long projectId) {
        if (nz(project.getBudget()).compareTo(BigDecimal.ZERO) > 0) {
            return project.getBudget();
        }
        return nz(projectAccountService.getOrCreate(projectId).getAdvanceAmount());
    }

    private BigDecimal quotaOf(PmProject project, Long projectId, BigDecimal percent) {
        return fundBase(project, projectId)
                .multiply(nz(percent))
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
    }

    private void assertSettleWithinQuota(Long projectId, BigDecimal addAmount) {
        PmProject project = projectMapper.selectById(projectId);
        var account = projectAccountService.getOrCreate(projectId);
        if (nz(addAmount).compareTo(nz(account.getBalance())) > 0) {
            throw new BusinessException("超过项目结余 ¥" + nz(account.getBalance()).toPlainString());
        }
        if (project == null) {
            return;
        }
        if (nz(project.getSettlePercent()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("分成比例为 0，无法分钱");
        }
        BigDecimal quota = quotaOf(project, projectId, project.getSettlePercent());
        BigDecimal used = nz(account.getSettleAmount()).add(nz(addAmount));
        if (quota.compareTo(BigDecimal.ZERO) > 0 && used.compareTo(quota) > 0) {
            throw new BusinessException("超过分成额度 ¥" + quota.toPlainString()
                    + "（已分 ¥" + nz(account.getSettleAmount()).toPlainString() + "）");
        }
    }

    private void effectSettle(WfApproval approval, JSONObject payload) {
        Map<Long, BigDecimal> shares = new HashMap<>();
        if (payload.containsKey("shares")) {
            JSONObject sharesObj = payload.getJSONObject("shares");
            for (String key : sharesObj.keySet()) {
                shares.put(Long.valueOf(key), sharesObj.getBigDecimal(key));
            }
        } else if (payload.containsKey("items")) {
            for (Object item : payload.getJSONArray("items")) {
                JSONObject row = JSONUtil.parseObj(item);
                shares.put(row.getLong("userId"), row.getBigDecimal("amount"));
            }
        }
        projectAccountService.settleToWallets(
                approval.getProjectId(),
                approval.getPoolId(),
                shares,
                approval.getId(),
                approval.getRemark());
    }

    private void effectRollback(WfApproval approval, JSONObject payload) {
        Long originId = payload.getLong("originApprovalId");
        BigDecimal amount = payload.getBigDecimal("amount", approval.getAmount());
        WfApproval origin = getById(originId);
        if (origin == null) {
            throw new BusinessException("原审批不存在");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("回退金额无效");
        }
        String originType = origin.getType();
        if (ApprovalTypes.PROJECT_ADVANCE.equals(originType) && origin.getProjectId() != null) {
            projectAccountService.reverseAdvance(origin.getProjectId(), origin.getPoolId(), amount,
                    approval.getId(), "回退原单 " + origin.getBizNo());
        } else if (ApprovalTypes.PROJECT_SETTLE.equals(originType)) {
            // 从原审批 payload 取每人金额，扣回个人并退回项目
            JSONObject originPayload = JSONUtil.parseObj(origin.getPayload());
            Map<Long, BigDecimal> shares = new HashMap<>();
            if (originPayload.containsKey("items")) {
                for (Object item : originPayload.getJSONArray("items")) {
                    JSONObject row = JSONUtil.parseObj(item);
                    shares.put(row.getLong("userId"), row.getBigDecimal("amount"));
                }
            }
            if (shares.isEmpty()) {
                throw new BusinessException("原分钱明细缺失，无法回退");
            }
            BigDecimal sum = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ratio = sum.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : amount.divide(sum, 8, java.math.RoundingMode.HALF_UP);
            Map<Long, BigDecimal> reverse = new HashMap<>();
            BigDecimal allocated = BigDecimal.ZERO;
            List<Map.Entry<Long, BigDecimal>> entries = new ArrayList<>(shares.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<Long, BigDecimal> e = entries.get(i);
                BigDecimal part;
                if (i == entries.size() - 1) {
                    part = amount.subtract(allocated);
                } else {
                    part = e.getValue().multiply(ratio).setScale(2, java.math.RoundingMode.HALF_UP);
                    allocated = allocated.add(part);
                }
                if (part.compareTo(BigDecimal.ZERO) > 0) {
                    reverse.put(e.getKey(), part);
                }
            }
            var account = projectAccountService.getOrCreate(origin.getProjectId());
            if (nz(account.getSettleAmount()).compareTo(amount) < 0) {
                throw new BusinessException("回退金额超过累计分成");
            }
            // 先扣个人（余额不足会失败），再退回项目
            Map<Long, BigDecimal[]> walletSnapshots = new HashMap<>();
            for (Map.Entry<Long, BigDecimal> e : reverse.entrySet()) {
                HrWallet walletBefore = walletService.getOrCreate(e.getKey());
                BigDecimal wb = walletBefore.getBalance();
                HrWallet walletAfter = walletService.changeBalance(e.getKey(), e.getValue().negate());
                walletSnapshots.put(e.getKey(), new BigDecimal[]{e.getValue(), wb, walletAfter.getBalance()});
            }
            BigDecimal before = nz(account.getBalance());
            account.setBalance(before.add(amount));
            account.setSettleAmount(nz(account.getSettleAmount()).subtract(amount));
            projectAccountService.updateById(account);
            Long batchId = writeSimpleLedger("ROLLBACK", "PROJECT", origin.getPoolId(), null, amount,
                    before, account.getBalance(), origin.getProjectId(), null, approval.getId(),
                    "分成回退入项目", "回退原单 " + origin.getBizNo());
            for (Map.Entry<Long, BigDecimal[]> e : walletSnapshots.entrySet()) {
                BigDecimal[] snap = e.getValue();
                writeSimpleLedger("ROLLBACK", "WALLET", origin.getPoolId(), e.getKey(),
                        snap[0].negate(), snap[1], snap[2], origin.getProjectId(),
                        batchId, approval.getId(),
                        "分成回退扣个人", "回退原单 " + origin.getBizNo());
            }
            PmProject project = projectMapper.selectById(origin.getProjectId());
            if (project != null) {
                project.setSettledAmount(nz(project.getSettledAmount()).subtract(amount));
                if (project.getSettledAmount().compareTo(BigDecimal.ZERO) < 0) {
                    project.setSettledAmount(BigDecimal.ZERO);
                }
                projectMapper.updateById(project);
            }
            projectAccountService.assertBalanced(origin.getProjectId());
        } else if (ApprovalTypes.REIMBURSE_PERSONAL.equals(originType)) {
            // 反向：个人扣回 + 公司入账
            HrWallet walletBefore = walletService.getOrCreate(origin.getApplicantId());
            BigDecimal wb = walletBefore.getBalance();
            HrWallet walletAfter = walletService.changeBalance(origin.getApplicantId(), amount.negate());
            FinPool pool = resolvePool(origin.getPoolId());
            BigDecimal poolBefore = pool.getBalance();
            creditPoolDirect(pool, amount);
            pool = poolMapper.selectById(pool.getId());
            Long walletLedger = writeSimpleLedger("ROLLBACK", "WALLET", pool.getId(), origin.getApplicantId(),
                    amount.negate(), wb, walletAfter.getBalance(), null, null, approval.getId(),
                    "个人报销回退扣款", "回退原单 " + origin.getBizNo());
            writeSimpleLedger("ROLLBACK", "POOL", pool.getId(), origin.getApplicantId(),
                    amount, poolBefore, pool.getBalance(), null, walletLedger, approval.getId(),
                    "个人报销回退入公司", "回退原单 " + origin.getBizNo());
        } else if (List.of(ApprovalTypes.REIMBURSE_PROJECT, ApprovalTypes.SALARY_APPLY).contains(originType)) {
            var account = projectAccountService.getOrCreate(origin.getProjectId());
            if (nz(account.getExpenseAmount()).compareTo(amount) < 0) {
                throw new BusinessException("回退金额超过项目累计支出");
            }
            BigDecimal before = nz(account.getBalance());
            account.setBalance(before.add(amount));
            account.setExpenseAmount(nz(account.getExpenseAmount()).subtract(amount));
            projectAccountService.updateById(account);
            writeSimpleLedger("ROLLBACK", "PROJECT", origin.getPoolId(), null, amount,
                    before, account.getBalance(), origin.getProjectId(), null, approval.getId(),
                    "项目支出回退", "回退原单 " + origin.getBizNo());
            projectAccountService.assertBalanced(origin.getProjectId());
        } else if (ApprovalTypes.LEDGER_REGISTER.equals(originType)) {
            effectLedgerRegisterRollback(approval, origin, amount);
        } else {
            throw new BusinessException("该审批类型暂不支持资金回退: " + originType);
        }
        origin.setStatus("ROLLED");
        updateById(origin);
        WfRollback rb = rollbackMapper.selectOne(new LambdaQueryWrapper<WfRollback>()
                .eq(WfRollback::getRollbackApprovalId, approval.getId())
                .last("LIMIT 1"));
        if (rb != null) {
            rb.setStatus("DONE");
            rollbackMapper.updateById(rb);
        }
    }

    private void saveMembers(Long projectId, List<PmProjectMember> members) {
        if (members == null || members.isEmpty()) {
            throw new BusinessException("分成参与人不能为空");
        }
        BigDecimal sum = members.stream()
                .map(m -> m.getPercent() == null ? BigDecimal.ZERO : m.getPercent())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("分成合计必须为 100%");
        }
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, projectId));
        for (PmProjectMember member : members) {
            member.setId(null);
            member.setProjectId(projectId);
            memberMapper.insert(member);
        }
    }

    private void validateSubmit(ApprovalSubmitRequest request, long applicantId) {
        String type = request.getType();
        if (ApprovalTypes.PROJECT_CREATE.equals(type)) {
            if (request.getPayload() == null || !StringUtils.hasText(String.valueOf(request.getPayload().get("name")))) {
                throw new BusinessException("请填写项目名称");
            }
        }
        if (ApprovalTypes.PROJECT_DELETE.equals(type) && request.getProjectId() == null) {
            throw new BusinessException("请选择要删除的项目");
        }
        if (List.of(ApprovalTypes.PROJECT_ADVANCE, ApprovalTypes.REIMBURSE_PROJECT,
                ApprovalTypes.SALARY_APPLY, ApprovalTypes.PROJECT_SETTLE,
                ApprovalTypes.RESERVE_RETURN, ApprovalTypes.SHARE_CONFIG).contains(type)) {
            if (request.getProjectId() == null) {
                throw new BusinessException("请选择项目");
            }
        }
        // 预留回笼金额取自项目已占用预留，提交时可不填金额
        if (List.of(ApprovalTypes.PROJECT_ADVANCE, ApprovalTypes.REIMBURSE_PROJECT,
                ApprovalTypes.SALARY_APPLY, ApprovalTypes.PROJECT_SETTLE).contains(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写金额");
            }
        }
        if (ApprovalTypes.REIMBURSE_PERSONAL.equals(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写报销金额");
            }
        }
        if (ApprovalTypes.REIMBURSE_PERSONAL.equals(type)
                || ApprovalTypes.REIMBURSE_PROJECT.equals(type)
                || ApprovalTypes.SALARY_APPLY.equals(type)) {
            if (request.getVoucherFileIds() == null || request.getVoucherFileIds().isEmpty()) {
                throw new BusinessException("请上传发票/凭证");
            }
        }
        if (ApprovalTypes.LEDGER_REGISTER.equals(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写金额");
            }
            Map<String, Object> payload = request.getPayload();
            Object bizTypeObj = payload == null ? null : payload.get("bizType");
            String bizType = bizTypeObj == null ? null : String.valueOf(bizTypeObj).trim();
            if (!StringUtils.hasText(bizType) || "null".equalsIgnoreCase(bizType)) {
                throw new BusinessException("请选择记账类型");
            }
            if (!List.of("INCOME", "EXPENSE").contains(bizType)) {
                throw new BusinessException("总账登记仅支持入账或出账，已取消划拨");
            }
            if ("INCOME".equals(bizType) && payload.get("channelId") == null) {
                throw new BusinessException("入账请选择收款渠道");
            }
        }
        if (ApprovalTypes.MONTHLY_VERIFY.equals(type)) {
            Map<String, Object> payload = request.getPayload();
            Object monthObj = payload == null ? null : payload.get("verifyMonth");
            String verifyMonth = monthObj == null ? null : String.valueOf(monthObj).trim();
            if (!StringUtils.hasText(verifyMonth) || "null".equalsIgnoreCase(verifyMonth)
                    || payload.get("channelId") == null) {
                throw new BusinessException("请选择核验月份和收款渠道");
            }
            if (request.getVoucherFileIds() == null || request.getVoucherFileIds().isEmpty()) {
                throw new BusinessException("请上传账户截图和流水凭证");
            }
        }
        if (ApprovalTypes.SHARE_CONFIG.equals(type) && request.getPayload() != null) {
            Map<String, Object> payload = request.getPayload();
            if (payload.get("expensePercent") != null || payload.get("reservePercent") != null
                    || payload.get("settlePercent") != null) {
                BigDecimal rp = toBd(payload.get("reservePercent"));
                BigDecimal sp = toBd(payload.get("settlePercent"));
                payload.put("expensePercent", BigDecimal.ZERO);
                assertSettleReservePercents(sp, rp);
            }
        }
        if (ApprovalTypes.RESERVE_RETURN.equals(type)) {
            if (request.getProjectId() == null) {
                throw new BusinessException("请选择项目");
            }
        }
    }

    private BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(v));
    }

    private WfApproval requirePending(Long id) {
        WfApproval approval = getById(id);
        if (approval == null) {
            throw new BusinessException("审批单不存在");
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new BusinessException("审批单不是待处理状态");
        }
        return approval;
    }

    private WfApprovalTask requireMyPendingTask(Long approvalId, long loginId) {
        WfApprovalTask task = taskMapper.selectOne(new LambdaQueryWrapper<WfApprovalTask>()
                .eq(WfApprovalTask::getApprovalId, approvalId)
                .eq(WfApprovalTask::getAssigneeId, loginId)
                .eq(WfApprovalTask::getAction, "PENDING")
                .last("LIMIT 1"));
        if (task == null) {
            throw new BusinessException("你没有待处理的审批任务");
        }
        return task;
    }

    private void addLog(Long approvalId, Long operatorId, String action, String remark) {
        WfApprovalLog log = new WfApprovalLog();
        log.setApprovalId(approvalId);
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setRemark(remark);
        log.setCreateTime(LocalDateTime.now());
        logMapper.insert(log);
    }

    private Map<String, Object> buildPayloadData(WfApproval approval) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", approval.getType());
        data.put("typeLabel", ApprovalTypes.label(approval.getType()));
        if (!StringUtils.hasText(approval.getPayload())) {
            return data;
        }
        JSONObject payload = JSONUtil.parseObj(approval.getPayload());
        data.putAll(payload);

        Set<Long> userIds = new HashSet<>();
        if (payload.containsKey("members") && payload.get("members") != null) {
            for (Object item : payload.getJSONArray("members")) {
                JSONObject m = JSONUtil.parseObj(item);
                Long uid = m.getLong("userId");
                if (uid != null) {
                    userIds.add(uid);
                }
            }
        }
        if (payload.containsKey("items") && payload.get("items") != null) {
            for (Object item : payload.getJSONArray("items")) {
                JSONObject m = JSONUtil.parseObj(item);
                Long uid = m.getLong("userId");
                if (uid != null) {
                    userIds.add(uid);
                }
            }
        }
        if (payload.getLong("ownerId") != null) {
            userIds.add(payload.getLong("ownerId"));
        }
        if (payload.getLong("userId") != null) {
            userIds.add(payload.getLong("userId"));
        }

        Map<Long, String> nameMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u ->
                    nameMap.put(u.getId(), u.getNickname() != null ? u.getNickname() : u.getUsername()));
        }

        if (payload.containsKey("members") && payload.get("members") != null) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (Object item : payload.getJSONArray("members")) {
                JSONObject m = JSONUtil.parseObj(item);
                Map<String, Object> row = new HashMap<>(m);
                Long uid = m.getLong("userId");
                row.put("userName", uid == null ? null : nameMap.get(uid));
                members.add(row);
            }
            data.put("members", members);
        }
        if (payload.containsKey("items") && payload.get("items") != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object item : payload.getJSONArray("items")) {
                JSONObject m = JSONUtil.parseObj(item);
                Map<String, Object> row = new HashMap<>(m);
                Long uid = m.getLong("userId");
                row.put("userName", uid == null ? null : nameMap.get(uid));
                items.add(row);
            }
            data.put("items", items);
        }
        if (payload.getLong("ownerId") != null) {
            data.put("ownerName", nameMap.get(payload.getLong("ownerId")));
        }
        if (payload.getLong("userId") != null) {
            data.put("userName", nameMap.get(payload.getLong("userId")));
        }

        String bizType = payload.getStr("bizType");
        if (StringUtils.hasText(bizType)) {
            data.put("bizTypeLabel", switch (bizType) {
                case "INCOME" -> "公司入账";
                case "EXPENSE" -> "公司出账";
                case "TRANSFER" -> "划拨";
                default -> bizType;
            });
        }
        return data;
    }

    private void fillExtras(List<WfApproval> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> userIds = list.stream().map(WfApproval::getApplicantId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> projectIds = list.stream().map(WfApproval::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u -> userMap.put(u.getId(), u));
        }
        Map<Long, PmProject> projectMap = new HashMap<>();
        if (!projectIds.isEmpty()) {
            projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds))
                    .forEach(p -> projectMap.put(p.getId(), p));
        }
        for (WfApproval a : list) {
            if (a.getApplicantId() != null) {
                SysUser u = userMap.get(a.getApplicantId());
                if (u != null) {
                    a.setApplicantName(u.getNickname() != null ? u.getNickname() : u.getUsername());
                }
            }
            if (a.getProjectId() != null) {
                PmProject p = projectMap.get(a.getProjectId());
                if (p != null) {
                    a.setProjectName(p.getName());
                }
            }
            a.setTypeLabel(ApprovalTypes.label(a.getType()));
            a.setStatusLabel(statusLabel(a.getStatus()));
            fillFlags(a);
        }
    }

    private void fillFlags(WfApproval a) {
        long loginId;
        try {
            loginId = StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return;
        }
        a.setCanWithdraw("PENDING".equals(a.getStatus()) && Objects.equals(a.getApplicantId(), loginId));
        a.setCanConfirm(Integer.valueOf(2).equals(a.getConfirmStatus()) && Objects.equals(a.getApplicantId(), loginId));
        a.setCanUploadReceipt(Integer.valueOf(1).equals(a.getConfirmStatus())
                && List.of("APPROVED", "TIMEOUT_PASS").contains(a.getStatus())
                && canFinanceHandle(loginId));
        a.setCanRollback(List.of("APPROVED", "TIMEOUT_PASS").contains(a.getStatus())
                && ApprovalTypes.canMoneyRollback(a.getType())
                && a.getAmount() != null && a.getAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                && (Objects.equals(a.getApplicantId(), loginId) || StpUtil.hasRole("admin") || canFinanceHandle(loginId)));
        if ("PENDING".equals(a.getStatus())) {
            Long cnt = taskMapper.selectCount(new LambdaQueryWrapper<WfApprovalTask>()
                    .eq(WfApprovalTask::getApprovalId, a.getId())
                    .eq(WfApprovalTask::getAssigneeId, loginId)
                    .eq(WfApprovalTask::getAction, "PENDING"));
            a.setCanHandle(cnt != null && cnt > 0);
        } else {
            a.setCanHandle(false);
        }
    }

    private boolean canFinanceHandle(long loginId) {
        List<String> roles = userService.getRoleCodes(loginId);
        if (roles.contains("admin") || roles.contains(ROLE_FINANCE)) {
            return true;
        }
        List<String> permissions = userService.getPermissions(loginId);
        return permissions != null && (permissions.contains("finance:ledger:add")
                || permissions.contains("finance:ledger:list")
                || permissions.contains("*:*:*"));
    }

    private void closePendingTasks(Long approvalId, String comment) {
        List<WfApprovalTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                .eq(WfApprovalTask::getApprovalId, approvalId)
                .eq(WfApprovalTask::getAction, "PENDING"));
        for (WfApprovalTask task : tasks) {
            task.setAction("CANCEL");
            task.setComment(comment);
            task.setActTime(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    /** 或签/超时：跳过未处理任务（与拒绝撤回的 CANCEL 区分） */
    private void skipPendingTasks(Long approvalId, String comment) {
        List<WfApprovalTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                .eq(WfApprovalTask::getApprovalId, approvalId)
                .eq(WfApprovalTask::getAction, "PENDING"));
        for (WfApprovalTask task : tasks) {
            task.setAction("SKIP");
            task.setComment(comment);
            task.setActTime(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    private Long writeSimpleLedger(String bizType, String accountType, Long poolId, Long userId,
                                   BigDecimal amount, BigDecimal before, BigDecimal after,
                                   Long projectId, Long relatedId, Long approvalId,
                                   String title, String remark) {
        FinLedger ledger = new FinLedger();
        ledger.setBizNo(bizNoGenerator.ledger());
        ledger.setBizType(bizType);
        ledger.setAccountType(accountType);
        ledger.setPoolId(poolId);
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setBeforeBalance(before);
        ledger.setAfterBalance(after);
        ledger.setProjectId(projectId);
        ledger.setRelatedId(relatedId);
        ledger.setApprovalId(approvalId);
        ledger.setTitle(title);
        ledger.setRemark(remark);
        ledger.setOccurTime(LocalDateTime.now());
        ledgerMapper.insert(ledger);
        if (relatedId == null) {
            FinLedger link = new FinLedger();
            link.setId(ledger.getId());
            link.setRelatedId(ledger.getId());
            ledgerMapper.updateById(link);
        }
        return ledger.getId();
    }

    private void fillTaskNames(List<WfApprovalTask> tasks) {
        Set<Long> ids = tasks.stream().map(WfApprovalTask::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, SysUser> map = userService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (WfApprovalTask t : tasks) {
            SysUser u = map.get(t.getAssigneeId());
            if (u != null) {
                t.setAssigneeName(u.getNickname() != null ? u.getNickname() : u.getUsername());
            }
        }
    }

    private void fillLogNames(List<WfApprovalLog> logs) {
        Set<Long> ids = logs.stream().map(WfApprovalLog::getOperatorId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, SysUser> map = userService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (WfApprovalLog log : logs) {
            SysUser u = map.get(log.getOperatorId());
            if (u != null) {
                log.setOperatorName(u.getNickname() != null ? u.getNickname() : u.getUsername());
            }
        }
    }

    private String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "PENDING" -> "待审批";
            case "APPROVED" -> "已通过";
            case "REJECTED" -> "已拒绝";
            case "WITHDRAWN" -> "已撤回";
            case "TIMEOUT_PASS" -> "超时通过";
            case "ROLLING" -> "回退中";
            case "ROLLED" -> "已回退";
            default -> status;
        };
    }

    private FinPool resolvePool(Long poolId) {
        FinPool pool = poolId != null ? poolMapper.selectById(poolId)
                : poolMapper.selectOne(new LambdaQueryWrapper<FinPool>().eq(FinPool::getIsDefault, 1).last("LIMIT 1"));
        if (pool == null) {
            throw new BusinessException("公司账户不存在");
        }
        return pool;
    }

    private void creditPoolDirect(FinPool pool, BigDecimal amount) {
        new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(poolMapper)
                .eq(FinPool::getId, pool.getId())
                .setSql("balance = balance + " + amount.toPlainString())
                .update();
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
