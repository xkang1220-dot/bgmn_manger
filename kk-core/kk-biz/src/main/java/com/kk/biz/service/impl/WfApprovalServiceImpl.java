package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.ApprovalQuery;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.RollbackRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinLedgerThreshold;
import com.kk.biz.entity.FinMonthVerify;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.HrPayMethod;
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
import com.kk.biz.service.FaAssetService;
import com.kk.biz.service.FinLedgerThresholdService;
import com.kk.biz.service.FinPayChannelService;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrArchiveService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.service.WfApprovalFlowService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.support.BizNoGenerator;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.biz.workflow.ProjectScales;
import com.kk.biz.util.ProjectCodeUtil;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysNotificationService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
    private static final ZoneId ZONE_CN = ZoneId.of("Asia/Shanghai");

    private final WfApprovalTaskMapper taskMapper;
    private final WfApprovalLogMapper logMapper;
    private final WfRollbackMapper rollbackMapper;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;
    private final FinProjectAccountService projectAccountService;
    private final FinanceService financeService;
    private final HrWalletService walletService;
    private final HrArchiveService archiveService;
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
    private final FinLedgerThresholdService ledgerThresholdService;
    private final FaAssetService faAssetService;
    @Lazy
    private final PmProjectService projectService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WfApproval submit(ApprovalSubmitRequest request) {
        return submitAs(StpUtil.getLoginIdAsLong(), request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WfApproval submitAs(Long applicantId, ApprovalSubmitRequest request) {
        if (applicantId == null) {
            throw new BusinessException("申请人不能为空");
        }
        String type = request.getType();
        if (!StringUtils.hasText(type)) {
            throw new BusinessException("审批类型不能为空");
        }
        validateSubmit(request, applicantId);

        Long companyId = resolveApprovalCompanyId(request, applicantId);
        WfApprovalFlow flow = approvalFlowService.requireEnabled(type, companyId);
        List<Long> assignees = approvalFlowService.resolveAssigneeIds(flow, companyId);
        String passMode = StringUtils.hasText(flow.getPassMode()) ? flow.getPassMode().toUpperCase() : "ALL";

        // 出账阈值：未达审批线禁止走审批单；达审批线强制全体股东会签（防绕过登记接口）
        if (ApprovalTypes.LEDGER_REGISTER.equals(type)) {
            Map<String, Object> payload = request.getPayload();
            Object bizTypeObj = payload == null ? null : payload.get("bizType");
            String bizType = bizTypeObj == null ? null : String.valueOf(bizTypeObj).trim();
            if ("EXPENSE".equals(bizType)) {
                FinLedgerThreshold cfg = ledgerThresholdService.findEnabled(companyId);
                if (cfg != null) {
                    BigDecimal amount = request.getAmount() == null ? BigDecimal.ZERO : request.getAmount();
                    BigDecimal approveLine = cfg.getApproveThreshold() == null ? BigDecimal.ZERO : cfg.getApproveThreshold();
                    if (amount.compareTo(approveLine) < 0) {
                        throw new BusinessException("未达审批线的出账请在「公司总账」登记，将按阈值直接出账或通知股东");
                    }
                    assignees = dataScopeService.listUserIdsByRoleCodeInCompany("shareholder", companyId);
                    passMode = "ALL";
                    if (assignees.isEmpty()) {
                        throw new BusinessException("该公司暂无股东，无法提交大额出账会签，请先配置股东角色");
                    }
                }
            }
        }

        // 登记入口 forceShareholderAll：无论原流程是否已是 ALL，都必须换成该公司股东（防财务角色误批大额出账）
        if (ApprovalTypes.LEDGER_REGISTER.equals(type) && request.getPayload() != null
                && Boolean.TRUE.equals(asBool(request.getPayload().get("forceShareholderAll")))) {
            assignees = dataScopeService.listUserIdsByRoleCodeInCompany("shareholder", companyId);
            passMode = "ALL";
            if (assignees.isEmpty()) {
                throw new BusinessException("该公司暂无股东，无法提交大额出账会签，请先配置股东角色");
            }
        }

        if (assignees.isEmpty()) {
            throw new BusinessException("未找到审批人，请先在「审批配置」中按该公司设置角色或指定人员");
        }

        WfApproval approval = new WfApproval();
        approval.setBizNo(bizNoGenerator.approval());
        approval.setType(type);
        approval.setCompanyId(companyId);
        approval.setTitle(StringUtils.hasText(request.getTitle())
                ? request.getTitle()
                : ApprovalTypes.label(type));
        approval.setStatus("PENDING");
        approval.setApplicantId(applicantId);
        approval.setAmount(request.getAmount());
        approval.setProjectId(request.getProjectId());
        approval.setPoolId(request.getPoolId());
        approval.setRemark(request.getRemark());
        attachPayMethodSnapshot(request, applicantId);
        approval.setPayload(request.getPayload() == null ? "{}" : JSONUtil.toJsonStr(request.getPayload()));
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
            // 1) 待我审批  2) 我发起的待确认到账  3) 待我上传财务回执
            List<Long> pendingTaskIds = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                            .eq(WfApprovalTask::getAssigneeId, loginId)
                            .eq(WfApprovalTask::getAction, "PENDING"))
                    .stream().map(WfApprovalTask::getApprovalId).filter(Objects::nonNull).distinct().toList();

            boolean finance = canFinanceHandle(loginId, null)
                    || dataScopeService.isGlobalAdmin(loginId);

            wrapper.and(w -> {
                boolean any = false;
                if (!pendingTaskIds.isEmpty()) {
                    w.nested(n -> n.in(WfApproval::getId, pendingTaskIds).eq(WfApproval::getStatus, "PENDING"));
                    any = true;
                }
                // 申请人：财务已回执，待确认到账
                if (any) {
                    w.or();
                }
                w.nested(n -> n.eq(WfApproval::getApplicantId, loginId)
                        .eq(WfApproval::getConfirmStatus, 2)
                        .in(WfApproval::getStatus, "APPROVED", "TIMEOUT_PASS"));
                any = true;
                // 财务：待上传回执
                if (finance) {
                    w.or().nested(n -> n.eq(WfApproval::getConfirmStatus, 1)
                            .in(WfApproval::getStatus, "APPROVED", "TIMEOUT_PASS"));
                }
                if (!any) {
                    w.eq(WfApproval::getId, -1L);
                }
            });
        }
        applyCompanyScope(wrapper, loginId);

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
        assertCanAccessApproval(approval, StpUtil.getLoginIdAsLong());
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
        fillFlowTip(approval);
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
        if (!Objects.equals(approval.getApplicantId(), loginId) && !dataScopeService.isGlobalAdmin(loginId)) {
            throw new BusinessException("只能撤回自己发起的审批");
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new BusinessException("仅待审批状态可撤回");
        }
        approval.setStatus("WITHDRAWN");
        updateById(approval);
        List<Long> assignees = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                        .eq(WfApprovalTask::getApprovalId, id)
                        .eq(WfApprovalTask::getAction, "PENDING"))
                .stream().map(WfApprovalTask::getAssigneeId).filter(Objects::nonNull).distinct().toList();
        closePendingTasks(id, "审批已撤回");
        addLog(id, loginId, "WITHDRAW", "申请人撤回");
        if (!assignees.isEmpty()) {
            notificationService.notifyUsers(
                    assignees.stream().filter(uid -> !Objects.equals(uid, loginId)).toList(),
                    "审批已撤回 · " + approval.getTitle(),
                    "申请人已撤回单号 " + approval.getBizNo() + "，无需再处理",
                    "approval", approval.getId(), "/workflow/center");
        }
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
        if (!canFinanceHandle(loginId, approval.getCompanyId())) {
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
        if (!Objects.equals(approval.getApplicantId(), loginId) && !dataScopeService.isGlobalAdmin(loginId)) {
            throw new BusinessException("仅申请人可确认到账");
        }
        if (approval.getConfirmStatus() == null || approval.getConfirmStatus() != 2) {
            throw new BusinessException("请等待财务上传回执后再确认");
        }
        approval.setConfirmStatus(3);
        updateById(approval);
        addLog(id, loginId, "CONFIRM", "申请人确认到账");
        executeMoneyEffect(approval);
        List<Long> financeIds = listFinanceUserIds(approval.getCompanyId());
        if (financeIds.isEmpty()) {
            financeIds = dataScopeService.listUserIdsByRoleCodeInCompany("admin", approval.getCompanyId());
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
        submit.setCompanyId(origin.getCompanyId());
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
                    addLog(fresh.getId(), null, "TIMEOUT_PASS", "超时未操作，自动通过");
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
            List<Long> financeIds = listFinanceUserIds(approval.getCompanyId());
            if (financeIds.isEmpty()) {
                financeIds = dataScopeService.listUserIdsByRoleCodeInCompany("admin", approval.getCompanyId());
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
            case ApprovalTypes.PROJECT_SCALE_CHANGE -> effectProjectScaleChange(approval, payload);
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
                String bizType = ApprovalTypes.SALARY_APPLY.equals(type) ? "SALARY" : "REIMBURSE";
                projectAccountService.expenseToWallet(
                        approval.getProjectId(),
                        approval.getApplicantId(),
                        approval.getAmount(),
                        approval.getId(),
                        bizType,
                        ApprovalTypes.label(type),
                        approval.getRemark());
            }
            case ApprovalTypes.SALARY_MONTHLY -> effectSalaryMonthly(approval, payload);
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
            case ApprovalTypes.ASSET_BORROW -> faAssetService.effectBorrow(approval);
            case ApprovalTypes.ASSET_RETURN -> faAssetService.effectReturn(approval);
            default -> {
            }
        }
        addLog(approval.getId(), null, "EFFECT", "审批生效，已执行业务动账");
    }

    private void effectProjectCreate(WfApproval approval, JSONObject payload) {
        PmProject project = new PmProject();
        project.setName(payload.getStr("name"));
        Long companyId = approval.getCompanyId();
        if (companyId == null) {
            throw new BusinessException("审批单缺少所属公司，无法创建项目");
        }
        project.setCode(allocateNextProjectCode(companyId));
        project.setOwnerId(payload.getLong("ownerId", approval.getApplicantId()));
        project.setPoolId(payload.getLong("poolId"));
        project.setBudget(payload.getBigDecimal("budget", BigDecimal.ZERO));
        project.setReserveAmount(payload.getBigDecimal("reserveAmount", BigDecimal.ZERO));
        project.setSettledAmount(BigDecimal.ZERO);
        project.setStatus(payload.getInt("status", 1));
        String scaleRaw = payload.getStr("scale");
        String scale = StringUtils.hasText(scaleRaw) ? ProjectScales.normalize(scaleRaw) : ProjectScales.KEY;
        project.setScale(scale);
        project.setApproveStatus(1);
        project.setDescription(payload.getStr("description"));
        project.setStartDate(parsePayloadLocalDate(payload, "startDate"));
        project.setEndDate(parsePayloadLocalDate(payload, "endDate"));
        project.setActualEndDate(parsePayloadLocalDate(payload, "actualEndDate"));
        // 创建人记申请人，而不是最后点通过的审批人
        project.setCreateBy(approval.getApplicantId());
        project.setUpdateBy(approval.getApplicantId());
        project.setCompanyId(companyId);
        Long ownerId = project.getOwnerId();
        if (ownerId != null
                && !dataScopeService.isGlobalAdmin(ownerId)
                && !dataScopeService.visibleCompanyIds(ownerId).contains(companyId)) {
            throw new BusinessException("不能跨公司设置项目负责人");
        }
        projectMapper.insert(project);
        // MetaObjectHandler 可能按审批人登录态写入 createBy；强制改回申请人，保证发起人可见
        Long applicantId = approval.getApplicantId();
        if (applicantId != null && project.getId() != null) {
            projectMapper.update(null, new LambdaUpdateWrapper<PmProject>()
                    .eq(PmProject::getId, project.getId())
                    .set(PmProject::getCreateBy, applicantId)
                    .set(PmProject::getUpdateBy, applicantId));
            project.setCreateBy(applicantId);
            project.setUpdateBy(applicantId);
        }
        projectAccountService.getOrCreate(project.getId());
        if (project.getReserveAmount() != null && project.getReserveAmount().compareTo(BigDecimal.ZERO) > 0) {
            var account = projectAccountService.getOrCreate(project.getId());
            account.setReserveAmount(project.getReserveAmount());
            projectAccountService.updateById(account);
        }
        // 成员：分成合计 100% 走财务规则；否则按项目参与人（percent 可为 0）
        if (payload.containsKey("members")) {
            List<PmProjectMember> members = JSONUtil.toList(payload.getJSONArray("members"), PmProjectMember.class);
            BigDecimal sum = members == null ? BigDecimal.ZERO : members.stream()
                    .map(m -> m.getPercent() == null ? BigDecimal.ZERO : m.getPercent())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(new BigDecimal("100")) == 0) {
                saveMembers(project.getId(), members);
            } else {
                saveCollaborationMembers(project.getId(), members);
            }
        }
        // 回写 projectId 便于查询
        approval.setProjectId(project.getId());
        updateById(approval);
        projectService.recordFlow(project.getId(), "CREATE", null, ProjectScales.label(scale),
                approval.getId(), "创建项目（" + ProjectScales.label(scale) + "）",
                approval.getApplicantId());
    }

    private void effectProjectScaleChange(WfApproval approval, JSONObject payload) {
        Long projectId = approval.getProjectId();
        if (projectId == null) {
            projectId = payload.getLong("projectId");
        }
        if (projectId == null) {
            throw new BusinessException("缺少项目ID");
        }
        String toScale = payload.getStr("toScale");
        if (!StringUtils.hasText(toScale)) {
            throw new BusinessException("缺少目标规模");
        }
        projectService.applyScaleChange(projectId, toScale, approval.getId(), approval.getApplicantId());
        approval.setProjectId(projectId);
        updateById(approval);
    }

    private void effectProjectDelete(WfApproval approval) {
        if (approval.getProjectId() == null) {
            throw new BusinessException("缺少项目ID");
        }
        projectService.recordFlow(approval.getProjectId(), "DELETE", null, null,
                approval.getId(), "删除项目", approval.getApplicantId());
        projectMapper.deleteById(approval.getProjectId());
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, approval.getProjectId()));
    }

    private void effectPersonalReimburse(WfApproval approval) {
        if (approval.getAmount() == null || approval.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        // 确认到账：公司总账出账 + 个人钱包入账（报销款归属个人）
        FinPool pool = resolvePool(approval.getPoolId(), approval.getCompanyId());
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
            existing.setCompanyId(approval.getCompanyId() != null ? approval.getCompanyId() : channel.getCompanyId());
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
            FinPool pool = poolMapper.selectById(poolId);
            if (pool == null) {
                throw new BusinessException("资金池不存在");
            }
            if (project.getCompanyId() != null && pool.getCompanyId() != null
                    && !Objects.equals(project.getCompanyId(), pool.getCompanyId())) {
                throw new BusinessException("资金池与项目不属于同一公司");
            }
            if (approval.getCompanyId() != null && pool.getCompanyId() != null
                    && !Objects.equals(approval.getCompanyId(), pool.getCompanyId())) {
                throw new BusinessException("资金池与审批单不属于同一公司");
            }
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
            throw new BusinessException("分成% + 预留% 必须为 100%，当前 " + sum + "%（工资/报销不占比例）");
        }
    }

    /** 支出不再校验比例额度，仅校验项目结余（动账时再扣项目并入钱包） */
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
            FinPool pool = resolvePool(origin.getPoolId(), origin.getCompanyId());
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
            if (origin.getApplicantId() == null) {
                throw new BusinessException("原单缺少申请人，无法回退个人钱包");
            }
            var account = projectAccountService.getOrCreate(origin.getProjectId());
            if (nz(account.getExpenseAmount()).compareTo(amount) < 0) {
                throw new BusinessException("回退金额超过项目累计支出");
            }
            // 新逻辑会写入 WALLET 正流入账；旧单只有项目扣款，回退时不能误扣钱包
            boolean creditedWallet = ledgerMapper.selectCount(new LambdaQueryWrapper<FinLedger>()
                    .eq(FinLedger::getApprovalId, origin.getId())
                    .eq(FinLedger::getAccountType, "WALLET")
                    .eq(FinLedger::getUserId, origin.getApplicantId())
                    .gt(FinLedger::getAmount, BigDecimal.ZERO)) > 0;

            BigDecimal before = nz(account.getBalance());
            account.setBalance(before.add(amount));
            account.setExpenseAmount(nz(account.getExpenseAmount()).subtract(amount));
            projectAccountService.updateById(account);
            Long projectLedger = writeSimpleLedger("ROLLBACK", "PROJECT", origin.getPoolId(), null, amount,
                    before, account.getBalance(), origin.getProjectId(), null, approval.getId(),
                    "项目支出回退", "回退原单 " + origin.getBizNo());
            if (creditedWallet) {
                HrWallet walletBefore = walletService.getOrCreate(origin.getApplicantId());
                if (nz(walletBefore.getBalance()).compareTo(amount) < 0) {
                    throw new BusinessException("申请人钱包余额不足，无法回退");
                }
                BigDecimal wb = walletBefore.getBalance();
                HrWallet walletAfter = walletService.changeBalance(origin.getApplicantId(), amount.negate());
                writeSimpleLedger("ROLLBACK", "WALLET", origin.getPoolId(), origin.getApplicantId(),
                        amount.negate(), wb, walletAfter.getBalance(), origin.getProjectId(), projectLedger, approval.getId(),
                        "项目支出回退扣个人钱包", "回退原单 " + origin.getBizNo());
            }
            projectAccountService.assertBalanced(origin.getProjectId());
        } else if (ApprovalTypes.SALARY_MONTHLY.equals(originType)) {
            effectSalaryMonthlyRollback(approval, origin);
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

    private void effectSalaryMonthly(WfApproval approval, JSONObject payload) {
        Long userId = payload.getLong("userId");
        if (userId == null) {
            throw new BusinessException("月度工资缺少收款人");
        }
        var items = payload.getJSONArray("items");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("月度工资明细为空");
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject row = items.getJSONObject(i);
            Long projectId = row.getLong("projectId");
            BigDecimal amount = row.getBigDecimal("amount");
            if (amount == null && row.get("amount") != null) {
                amount = new BigDecimal(String.valueOf(row.get("amount")));
            }
            if (projectId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("月度工资明细无效");
            }
            assertExpenseWithinQuota(projectId, amount);
            String projectName = row.getStr("projectName", "#" + projectId);
            projectAccountService.expenseToWallet(
                    projectId,
                    userId,
                    amount,
                    approval.getId(),
                    "SALARY",
                    "月度工资 · " + projectName,
                    approval.getRemark());
        }
    }

    private void effectSalaryMonthlyRollback(WfApproval approval, WfApproval origin) {
        JSONObject payload = JSONUtil.parseObj(origin.getPayload());
        Long userId = payload.getLong("userId");
        if (userId == null) {
            throw new BusinessException("原单缺少收款人，无法回退");
        }
        var items = payload.getJSONArray("items");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("原单明细为空，无法回退");
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject row = items.getJSONObject(i);
            Long projectId = row.getLong("projectId");
            BigDecimal amount = row.getBigDecimal("amount");
            if (projectId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            var account = projectAccountService.getOrCreate(projectId);
            if (nz(account.getExpenseAmount()).compareTo(amount) < 0) {
                throw new BusinessException("回退金额超过项目累计支出");
            }
            BigDecimal before = nz(account.getBalance());
            account.setBalance(before.add(amount));
            account.setExpenseAmount(nz(account.getExpenseAmount()).subtract(amount));
            projectAccountService.updateById(account);
            Long projectLedger = writeSimpleLedger("ROLLBACK", "PROJECT", origin.getPoolId(), null, amount,
                    before, account.getBalance(), projectId, null, approval.getId(),
                    "月度工资回退", "回退原单 " + origin.getBizNo());
            HrWallet walletBefore = walletService.getOrCreate(userId);
            if (nz(walletBefore.getBalance()).compareTo(amount) < 0) {
                throw new BusinessException("收款人钱包余额不足，无法回退");
            }
            BigDecimal wb = walletBefore.getBalance();
            HrWallet walletAfter = walletService.changeBalance(userId, amount.negate());
            writeSimpleLedger("ROLLBACK", "WALLET", origin.getPoolId(), userId,
                    amount.negate(), wb, walletAfter.getBalance(), projectId, projectLedger, approval.getId(),
                    "月度工资回退扣个人钱包", "回退原单 " + origin.getBizNo());
            projectAccountService.assertBalanced(projectId);
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
        PmProject project = projectMapper.selectById(projectId);
        Long companyId = project == null ? null : project.getCompanyId();
        for (PmProjectMember member : members) {
            if (member.getUserId() != null && companyId != null
                    && !dataScopeService.isGlobalAdmin(member.getUserId())
                    && !dataScopeService.visibleCompanyIds(member.getUserId()).contains(companyId)) {
                throw new BusinessException("不能跨公司添加分成参与人");
            }
        }
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, projectId));
        for (PmProjectMember member : members) {
            member.setId(null);
            member.setProjectId(projectId);
            memberMapper.insert(member);
        }
    }

    /** 项目协作参与人（不校验分成合计；职责必填） */
    private void saveCollaborationMembers(Long projectId, List<PmProjectMember> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        PmProject project = projectMapper.selectById(projectId);
        Long companyId = project == null ? null : project.getCompanyId();
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, projectId));
        Set<Long> seen = new HashSet<>();
        for (PmProjectMember member : members) {
            if (member == null || member.getUserId() == null || !seen.add(member.getUserId())) {
                continue;
            }
            if (companyId != null
                    && !dataScopeService.isGlobalAdmin(member.getUserId())
                    && !dataScopeService.visibleCompanyIds(member.getUserId()).contains(companyId)) {
                throw new BusinessException("不能跨公司添加项目参与人");
            }
            String duty = member.getLayer() == null ? "" : member.getLayer().trim();
            if (!StringUtils.hasText(duty)) {
                throw new BusinessException("请填写项目参与人职责");
            }
            if (duty.length() > 64) {
                throw new BusinessException("参与人职责不能超过 64 字");
            }
            member.setId(null);
            member.setProjectId(projectId);
            member.setLayer(duty);
            if (member.getPercent() == null) {
                member.setPercent(BigDecimal.ZERO);
            }
            memberMapper.insert(member);
        }
    }

    private String allocateNextProjectCode(Long companyId) {
        SysDept company = deptService.getById(companyId);
        String prefix = ProjectCodeUtil.companyPrefix(company == null ? null : company.getName());
        String marker = prefix + "-";
        List<PmProject> coded = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getCompanyId, companyId)
                .likeRight(PmProject::getCode, marker)
                .select(PmProject::getCode));
        int maxSeq = 0;
        for (PmProject p : coded) {
            if (p == null || !StringUtils.hasText(p.getCode()) || !p.getCode().startsWith(marker)) {
                continue;
            }
            String tail = p.getCode().substring(marker.length()).trim();
            if (tail.matches("\\d+")) {
                try {
                    maxSeq = Math.max(maxSeq, Integer.parseInt(tail));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return ProjectCodeUtil.formatCode(prefix, maxSeq + 1);
    }

    private Long resolveApprovalCompanyId(ApprovalSubmitRequest request, long applicantId) {
        Long companyId = request.getCompanyId();
        if (companyId == null && request.getPayload() != null && request.getPayload().get("companyId") != null) {
            Object raw = request.getPayload().get("companyId");
            if (raw instanceof Number n) {
                companyId = n.longValue();
            } else {
                String text = String.valueOf(raw).trim();
                if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
                    try {
                        companyId = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        throw new BusinessException("所属公司参数无效");
                    }
                }
            }
        }
        if (companyId != null) {
            assertResourceInVisibleCompanies(applicantId, companyId, "无权在该公司发起审批");
        }
        if (request.getProjectId() != null) {
            PmProject project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new BusinessException("项目不存在");
            }
            assertResourceInVisibleCompanies(applicantId, project.getCompanyId(), "无权操作其他公司的项目");
            if (companyId == null) {
                companyId = project.getCompanyId();
            } else if (project.getCompanyId() != null && !Objects.equals(companyId, project.getCompanyId())) {
                throw new BusinessException("所选公司与项目不属于同一公司");
            }
        }
        if (request.getPoolId() != null) {
            FinPool pool = poolMapper.selectById(request.getPoolId());
            if (pool == null) {
                throw new BusinessException("资金池不存在");
            }
            assertResourceInVisibleCompanies(applicantId, pool.getCompanyId(), "无权操作其他公司的资金池");
            if (companyId == null) {
                companyId = pool.getCompanyId();
            } else if (pool.getCompanyId() != null && !Objects.equals(companyId, pool.getCompanyId())) {
                throw new BusinessException("所选公司与资金池不属于同一公司");
            }
        }
        if (companyId == null) {
            if (request.getProjectId() != null || request.getPoolId() != null) {
                throw new BusinessException("关联项目或资金池缺少所属公司，无法提交");
            }
            throw new BusinessException("请选择所属公司");
        }
        return companyId;
    }

    private void assertResourceInVisibleCompanies(long userId, Long companyId, String message) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        if (companyId == null) {
            throw new BusinessException(message);
        }
        if (!dataScopeService.visibleCompanyIds(userId).contains(companyId)) {
            throw new BusinessException(message);
        }
    }

    private void assertCanAccessApproval(WfApproval approval, long loginId) {
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        if (Objects.equals(approval.getApplicantId(), loginId)) {
            return;
        }
        Long assigned = taskMapper.selectCount(new LambdaQueryWrapper<WfApprovalTask>()
                .eq(WfApprovalTask::getApprovalId, approval.getId())
                .eq(WfApprovalTask::getAssigneeId, loginId));
        if (assigned != null && assigned > 0) {
            return;
        }
        if (canFinanceHandle(loginId, approval.getCompanyId())) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        Set<Long> users = approval.getCompanyId() == null
                ? Set.of()
                : dataScopeService.visibleUserIdsInCompany(loginId, approval.getCompanyId());
        if (approval.getCompanyId() != null && companies.contains(approval.getCompanyId())
                && approval.getApplicantId() != null && users.contains(approval.getApplicantId())) {
            return;
        }
        throw new BusinessException("无权查看该审批单");
    }

    private void applyCompanyScope(LambdaQueryWrapper<WfApproval> wrapper, long loginId) {
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (companies.isEmpty()) {
            wrapper.eq(WfApproval::getId, -1L);
            return;
        }
        Set<Long> financeCompanies = companies.stream()
                .filter(c -> canFinanceHandle(loginId, c))
                .collect(Collectors.toSet());
        List<Long> myApprovalIds = taskMapper.selectList(new LambdaQueryWrapper<WfApprovalTask>()
                        .eq(WfApprovalTask::getAssigneeId, loginId)
                        .select(WfApprovalTask::getApprovalId))
                .stream().map(WfApprovalTask::getApprovalId).filter(Objects::nonNull).distinct().toList();
        wrapper.and(outer -> {
            boolean any = false;
            if (!myApprovalIds.isEmpty()) {
                outer.or().in(WfApproval::getId, myApprovalIds);
                any = true;
            }
            for (Long companyId : companies) {
                if (financeCompanies.contains(companyId)) {
                    outer.or().eq(WfApproval::getCompanyId, companyId);
                    any = true;
                    continue;
                }
                Set<Long> users = dataScopeService.visibleUserIdsInCompany(loginId, companyId);
                if (users.isEmpty()) {
                    continue;
                }
                outer.or(w -> w.eq(WfApproval::getCompanyId, companyId)
                        .in(WfApproval::getApplicantId, users));
                any = true;
            }
            if (!any) {
                outer.eq(WfApproval::getId, -1L);
            }
        });
    }

    private void attachPayMethodSnapshot(ApprovalSubmitRequest request, long applicantId) {
        String type = request.getType();
        if (!List.of(ApprovalTypes.SALARY_APPLY, ApprovalTypes.REIMBURSE_PROJECT, ApprovalTypes.REIMBURSE_PERSONAL)
                .contains(type)) {
            return;
        }
        Map<String, Object> payload = request.getPayload() == null
                ? new HashMap<>()
                : new HashMap<>(request.getPayload());
        Object rawId = payload.get("payMethodId");
        if (rawId == null || !StringUtils.hasText(String.valueOf(rawId)) || "null".equalsIgnoreCase(String.valueOf(rawId))) {
            request.setPayload(payload);
            return;
        }
        Long methodId;
        try {
            if (rawId instanceof Number number) {
                methodId = number.longValue();
            } else {
                methodId = Long.valueOf(String.valueOf(rawId).trim());
            }
        } catch (Exception e) {
            throw new BusinessException("收款方式无效");
        }
        HrPayMethod method = archiveService.getOwnedMethod(applicantId, methodId);
        if (method == null) {
            throw new BusinessException("收款方式不存在或不属于当前用户");
        }
        Map<String, Object> snap = new HashMap<>();
        snap.put("methodId", method.getId());
        snap.put("methodType", method.getMethodType());
        snap.put("methodTypeLabel", method.getMethodTypeLabel());
        snap.put("accountName", method.getAccountName());
        snap.put("accountNo", method.getAccountNo());
        snap.put("bankName", method.getBankName());
        payload.put("payMethod", snap);
        request.setPayload(payload);
    }

    private void validateSubmit(ApprovalSubmitRequest request, long applicantId) {
        String type = request.getType();
        if (ApprovalTypes.PROJECT_CREATE.equals(type)) {
            if (request.getPayload() == null || !StringUtils.hasText(String.valueOf(request.getPayload().get("name")))) {
                throw new BusinessException("请填写项目名称");
            }
            Object membersObj = request.getPayload().get("members");
            if (membersObj instanceof List<?> list && !list.isEmpty()) {
                Set<Long> seen = new HashSet<>();
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    JSONObject m = item instanceof JSONObject jo ? jo : JSONUtil.parseObj(item);
                    Long uid = m.getLong("userId");
                    if (uid == null) {
                        continue;
                    }
                    if (!seen.add(uid)) {
                        throw new BusinessException("项目参与人不能重复");
                    }
                    String duty = m.getStr("layer");
                    if (!StringUtils.hasText(duty == null ? null : duty.trim())) {
                        throw new BusinessException("请填写项目参与人职责");
                    }
                    if (duty.trim().length() > 64) {
                        throw new BusinessException("参与人职责不能超过 64 字");
                    }
                }
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
        if (ApprovalTypes.SALARY_MONTHLY.equals(type)) {
            if (request.getCompanyId() == null
                    && (request.getPayload() == null || request.getPayload().get("companyId") == null)) {
                // company 也可由 resolve 推断，但月度单应显式带公司
            }
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写月度工资合计金额");
            }
            if (request.getPayload() == null || request.getPayload().get("userId") == null) {
                throw new BusinessException("月度工资缺少收款人");
            }
            Object items = request.getPayload().get("items");
            if (!(items instanceof List<?> list) || list.isEmpty()) {
                throw new BusinessException("月度工资明细不能为空");
            }
        }
        if (ApprovalTypes.REIMBURSE_PERSONAL.equals(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写报销金额");
            }
        }
        if (ApprovalTypes.REIMBURSE_PERSONAL.equals(type)
                || ApprovalTypes.REIMBURSE_PROJECT.equals(type)) {
            if (request.getVoucherFileIds() == null || request.getVoucherFileIds().isEmpty()) {
                throw new BusinessException("请上传发票/凭证");
            }
        }
        if (ApprovalTypes.LEDGER_REGISTER.equals(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写金额");
            }
            if (request.getPoolId() == null) {
                throw new BusinessException("请选择资金池");
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
            if (request.getPoolId() == null) {
                throw new BusinessException("请选择资金池/渠道所属公司资金池");
            }
            Map<String, Object> payload = request.getPayload();
            Object monthObj = payload == null ? null : payload.get("verifyMonth");
            String verifyMonth = monthObj == null ? null : String.valueOf(monthObj).trim();
            if (!StringUtils.hasText(verifyMonth) || "null".equalsIgnoreCase(verifyMonth)
                    || payload == null || payload.get("channelId") == null) {
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
        if (ApprovalTypes.ASSET_BORROW.equals(type) || ApprovalTypes.ASSET_RETURN.equals(type)) {
            Map<String, Object> payload = request.getPayload();
            if (payload == null) {
                payload = new HashMap<>();
                request.setPayload(payload);
            }
            Object assetIdObj = payload.get("assetId");
            Long assetId = null;
            if (assetIdObj instanceof Number n) {
                assetId = n.longValue();
            } else if (assetIdObj != null) {
                String text = String.valueOf(assetIdObj).trim();
                if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
                    try {
                        assetId = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        throw new BusinessException("资产参数无效");
                    }
                }
            }
            if (assetId == null) {
                throw new BusinessException("请选择资产");
            }
            if (ApprovalTypes.ASSET_BORROW.equals(type)) {
                faAssetService.assertCanBorrow(assetId, applicantId);
                var asset = faAssetService.getById(assetId);
                if (asset != null) {
                    request.setAmount(asset.getOriginalValue());
                    request.setCompanyId(asset.getCompanyId());
                    payload.put("assetCode", asset.getAssetCode());
                    payload.put("assetName", asset.getName());
                    payload.put("originalValue", asset.getOriginalValue());
                    payload.put("companyId", asset.getCompanyId());
                }
            } else {
                faAssetService.assertCanReturn(assetId, applicantId);
                var asset = faAssetService.getById(assetId);
                if (asset != null) {
                    request.setAmount(asset.getOriginalValue());
                    request.setCompanyId(asset.getCompanyId());
                    payload.put("assetCode", asset.getAssetCode());
                    payload.put("assetName", asset.getName());
                    payload.put("originalValue", asset.getOriginalValue());
                    payload.put("holderUserId", asset.getHolderUserId());
                    payload.put("companyId", asset.getCompanyId());
                }
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
        putIsoDate(data, payload, "startDate");
        putIsoDate(data, payload, "endDate");
        putIsoDate(data, payload, "actualEndDate");

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
        Set<Long> companyIds = list.stream().map(WfApproval::getCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u -> userMap.put(u.getId(), u));
        }
        Map<Long, PmProject> projectMap = new HashMap<>();
        if (!projectIds.isEmpty()) {
            projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds))
                    .forEach(p -> projectMap.put(p.getId(), p));
        }
        Map<Long, String> companyNameMap = new HashMap<>();
        if (!companyIds.isEmpty()) {
            deptService.listByIds(companyIds).forEach(d -> companyNameMap.put(d.getId(), d.getName()));
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
            if (a.getCompanyId() != null) {
                a.setCompanyName(companyNameMap.get(a.getCompanyId()));
            }
            a.setTypeLabel(ApprovalTypes.label(a.getType()));
            a.setStatusLabel(displayStatus(a));
            fillFlags(a);
        }
    }

    private String displayStatus(WfApproval a) {
        if (a == null) {
            return "";
        }
        if (List.of("APPROVED", "TIMEOUT_PASS").contains(a.getStatus())) {
            if (Integer.valueOf(1).equals(a.getConfirmStatus())) {
                return "待财务回执";
            }
            if (Integer.valueOf(2).equals(a.getConfirmStatus())) {
                return "待确认到账";
            }
        }
        return statusLabel(a.getStatus());
    }

    private void fillFlags(WfApproval a) {
        long loginId;
        try {
            loginId = StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return;
        }
        a.setCanWithdraw("PENDING".equals(a.getStatus())
                && a.getApplicantId() != null
                && a.getApplicantId() == loginId);
        a.setCanConfirm(Integer.valueOf(2).equals(a.getConfirmStatus()) && Objects.equals(a.getApplicantId(), loginId));
        a.setCanUploadReceipt(Integer.valueOf(1).equals(a.getConfirmStatus())
                && List.of("APPROVED", "TIMEOUT_PASS").contains(a.getStatus())
                && canFinanceHandle(loginId, a.getCompanyId()));
        a.setCanRollback(List.of("APPROVED", "TIMEOUT_PASS").contains(a.getStatus())
                && ApprovalTypes.canMoneyRollback(a.getType())
                && a.getAmount() != null && a.getAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                && (Objects.equals(a.getApplicantId(), loginId)
                || dataScopeService.isGlobalAdmin(loginId)
                || canFinanceHandle(loginId, a.getCompanyId())));
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

    private boolean canFinanceHandle(long loginId, Long companyId) {
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return true;
        }
        if (companyId != null && !dataScopeService.visibleCompanyIds(loginId).contains(companyId)) {
            return false;
        }
        return dataScopeService.hasRoleInCompany(loginId, ROLE_FINANCE, companyId)
                || dataScopeService.hasRoleInCompany(loginId, "Gold", companyId);
    }

    private List<Long> listFinanceUserIds(Long companyId) {
        Set<Long> ids = new HashSet<>();
        ids.addAll(dataScopeService.listUserIdsByRoleCodeInCompany(ROLE_FINANCE, companyId));
        ids.addAll(dataScopeService.listUserIdsByRoleCodeInCompany("Gold", companyId));
        return new ArrayList<>(ids);
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

    private void fillFlowTip(WfApproval approval) {
        boolean any = "ANY".equalsIgnoreCase(approval.getPassMode());
        approval.setPassModeLabel(any ? "或签（一人通过即可）" : "会签（须全部通过）");
        StringBuilder tip = new StringBuilder("已提交审批：").append(approval.getPassModeLabel());
        if (approval.getAutoPass() != null && approval.getAutoPass() == 1 && approval.getTimeoutAt() != null) {
            LocalDateTime start = approval.getCreateTime() != null ? approval.getCreateTime() : LocalDateTime.now();
            long hours = java.time.Duration.between(start, approval.getTimeoutAt()).toHours();
            if (hours < 1) {
                hours = 1;
            }
            tip.append("，").append(hours).append("小时未操作自动通过");
        } else {
            tip.append("，无超时自动通过");
        }
        if (approval.getTasks() != null && !approval.getTasks().isEmpty()) {
            String names = approval.getTasks().stream()
                    .map(WfApprovalTask::getAssigneeName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.joining("、"));
            if (StringUtils.hasText(names)) {
                tip.append("；审批人：").append(names);
            }
        }
        approval.setFlowTip(tip.toString());
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

    private FinPool resolvePool(Long poolId, Long companyId) {
        FinPool pool;
        if (poolId != null) {
            pool = poolMapper.selectById(poolId);
        } else if (companyId != null) {
            pool = poolMapper.selectOne(new LambdaQueryWrapper<FinPool>()
                    .eq(FinPool::getIsDefault, 1)
                    .eq(FinPool::getCompanyId, companyId)
                    .last("LIMIT 1"));
            if (pool == null) {
                pool = poolMapper.selectOne(new LambdaQueryWrapper<FinPool>()
                        .eq(FinPool::getCompanyId, companyId)
                        .orderByAsc(FinPool::getId)
                        .last("LIMIT 1"));
            }
        } else {
            throw new BusinessException("无法确定资金池所属公司");
        }
        if (pool == null) {
            throw new BusinessException("公司账户不存在");
        }
        if (companyId != null && pool.getCompanyId() != null && !companyId.equals(pool.getCompanyId())) {
            throw new BusinessException("资金池与审批单不属于同一公司");
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

    private boolean asBool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private void putIsoDate(Map<String, Object> data, JSONObject payload, String key) {
        if (!payload.containsKey(key) || payload.get(key) == null) {
            return;
        }
        try {
            LocalDate date = parsePayloadLocalDate(payload, key);
            if (date != null) {
                data.put(key, date.toString());
            }
        } catch (BusinessException ignored) {
            // 详情展示时保留原值，避免坏数据导致整单打不开
        }
    }

    private LocalDate parsePayloadLocalDate(JSONObject payload, String key) {
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate date) {
            return date;
        }
        if (raw instanceof java.util.Date date) {
            return date.toInstant().atZone(ZONE_CN).toLocalDate();
        }
        if (raw instanceof Number number) {
            return localDateFromEpoch(number.longValue());
        }
        String text = String.valueOf(raw).trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        if (text.matches("\\d{10,13}")) {
            return localDateFromEpoch(Long.parseLong(text));
        }
        if (text.length() >= 10 && Character.isDigit(text.charAt(0))) {
            try {
                return LocalDate.parse(text.substring(0, 10));
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        throw new BusinessException("日期格式无法识别：" + text);
    }

    private LocalDate localDateFromEpoch(long value) {
        Instant instant = value >= 1_000_000_000_000L
                ? Instant.ofEpochMilli(value)
                : Instant.ofEpochSecond(value);
        return instant.atZone(ZONE_CN).toLocalDate();
    }
}
