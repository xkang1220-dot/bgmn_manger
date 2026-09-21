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
import com.kk.biz.entity.FinProjectAccount;
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
import com.kk.biz.service.HrLeaveService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.service.WalletWithdrawTaxService;
import com.kk.biz.service.WfApprovalFlowService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.dto.WithdrawTaxCalcResult;
import com.kk.biz.support.BizNoGenerator;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.biz.workflow.ProjectFundTypes;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final WalletWithdrawTaxService walletWithdrawTaxService;
    /** 与项目服务单向依赖；字段注入 + @Lazy，避免构造器循环 */
    @Lazy
    @Autowired
    private PmProjectService projectService;
    @Lazy
    @Autowired
    private HrLeaveService leaveService;

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
        // 提现税额必须按最终落单公司重算，避免 validate 阶段公司未解析时误用一口价
        if (ApprovalTypes.WALLET_WITHDRAW.equals(type)) {
            applyWithdrawTaxPayload(request, companyId);
        }
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

        if (ApprovalTypes.WALLET_WITHDRAW.equals(type)) {
            walletService.freeze(applicantId, request.getAmount());
            addLog(approval.getId(), applicantId, "FREEZE", "提现申请冻结钱包 ¥" + request.getAmount().toPlainString());
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
        releaseWithdrawFreeze(approval, loginId, "审批拒绝，解冻提现金额");
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
        releaseWithdrawFreeze(approval, loginId, "申请人撤回，解冻提现金额");
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
        addLog(id, loginId, "CONFIRM",
                ApprovalTypes.WALLET_WITHDRAW.equals(approval.getType()) ? "申请人确认提现完成" : "申请人确认到账");
        executeMoneyEffect(approval);
        List<Long> financeIds = listFinanceUserIds(approval.getCompanyId());
        if (financeIds.isEmpty()) {
            financeIds = dataScopeService.listUserIdsByRoleCodeInCompany("admin", approval.getCompanyId());
        }
        boolean withdraw = ApprovalTypes.WALLET_WITHDRAW.equals(approval.getType());
        String moneyTip;
        if (withdraw) {
            moneyTip = "申请人已确认，单号 " + approval.getBizNo() + "，钱包已扣款"
                    + (isWithdrawWithVoucher(JSONUtil.parseObj(approval.getPayload()))
                            ? "（有凭证免税）"
                            : "（税额仅供线下到手参考，公司余额不变）");
        } else if (ApprovalTypes.REIMBURSE_PERSONAL.equals(approval.getType())) {
            moneyTip = "申请人已确认，单号 " + approval.getBizNo() + "，公司总账已扣款";
        } else {
            moneyTip = "申请人已确认，单号 " + approval.getBizNo() + "，资金已入账";
        }
        notificationService.notifyUsers(
                financeIds.stream().filter(uid -> !Objects.equals(uid, loginId)).toList(),
                (withdraw ? "已确认提现 · " : "已确认到账 · ") + approval.getTitle(),
                moneyTip,
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelPendingByProject(Long projectId, String reason) {
        if (projectId == null) {
            return 0;
        }
        String remark = StringUtils.hasText(reason) ? reason : "项目已删除，审批自动关闭";
        List<WfApproval> list = list(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getProjectId, projectId)
                .eq(WfApproval::getStatus, "PENDING"));
        if (list.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (WfApproval approval : list) {
            approval.setStatus("WITHDRAWN");
            updateById(approval);
            closePendingTasks(approval.getId(), remark);
            releaseWithdrawFreeze(approval, null, remark);
            addLog(approval.getId(), null, "CANCEL", remark);
            count++;
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
            case ApprovalTypes.PROJECT_ADVANCE_RETURN -> {
                JSONObject retPayload = JSONUtil.parseObj(approval.getPayload());
                projectAccountService.reverseAdvance(
                        approval.getProjectId(),
                        approval.getPoolId(),
                        approval.getAmount(),
                        retPayload.getBigDecimal("sharePendingAmount"),
                        retPayload.getBigDecimal("nonShareAmount"),
                        approval.getId(),
                        approval.getRemark());
            }
            case ApprovalTypes.REIMBURSE_PROJECT, ApprovalTypes.SALARY_APPLY, ApprovalTypes.PROJECT_BALANCE_APPLY -> {
                String fundType = ProjectFundTypes.normalize(payload.getStr("fundType"));
                assertExpenseWithinQuota(approval.getProjectId(), approval.getAmount(), fundType);
                String bizType = ApprovalTypes.SALARY_APPLY.equals(type) ? "SALARY"
                        : ApprovalTypes.PROJECT_BALANCE_APPLY.equals(type) ? "PAYOUT" : "REIMBURSE";
                projectAccountService.expenseToWallet(
                        approval.getProjectId(),
                        approval.getApplicantId(),
                        approval.getAmount(),
                        fundType,
                        approval.getId(),
                        bizType,
                        ApprovalTypes.label(type),
                        approval.getRemark());
            }
            case ApprovalTypes.DIRECT_PAYOUT -> effectDirectPayout(approval, payload);
            case ApprovalTypes.LEAVE_APPLY -> effectLeaveApply(approval, payload);
            case ApprovalTypes.SALARY_MONTHLY -> effectSalaryMonthly(approval, payload);
            case ApprovalTypes.REIMBURSE_PERSONAL -> effectPersonalReimburse(approval);
            case ApprovalTypes.WALLET_WITHDRAW -> effectWalletWithdraw(approval, payload);
            case ApprovalTypes.SHARE_CONFIG -> effectShareConfig(approval, payload);
            case ApprovalTypes.PROJECT_SETTLE -> {
                assertSettleWithinQuota(approval.getProjectId(), approval.getAmount());
                effectSettle(approval, payload);
            }
            case ApprovalTypes.PROJECT_SHARE_PERIOD -> effectSharePeriod(approval, payload);
            case ApprovalTypes.RESERVE_RETURN -> {
                if (StringUtils.hasText(payload.getStr("periodMonth"))) {
                    // 自然月预留回笼：仅退当前预留占用（与「结余回公司」拆分入账无关）
                    projectAccountService.returnReserveHeldToCompany(
                            approval.getProjectId(), approval.getPoolId(), approval.getId(), approval.getRemark());
                } else {
                    BigDecimal s = nz(payload.getBigDecimal("sharePendingAmount")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal n = nz(payload.getBigDecimal("nonShareAmount")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal h = nz(payload.getBigDecimal("reserveHeldAmount")).setScale(2, RoundingMode.HALF_UP);
                    boolean hasSplit = payload.containsKey("sharePendingAmount")
                            || payload.containsKey("nonShareAmount")
                            || payload.containsKey("reserveHeldAmount");
                    BigDecimal sum = s.add(n).add(h).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal approvalAmt = approval.getAmount() == null
                            ? null : approval.getAmount().setScale(2, RoundingMode.HALF_UP);
                    if (hasSplit) {
                        // 有拆分字段：必须按拆分动账，拆分为 0 直接失败（禁止回退到清仓）
                        if (sum.compareTo(BigDecimal.ZERO) <= 0) {
                            throw new BusinessException("结余回公司金额无效");
                        }
                        if (approvalAmt != null && approvalAmt.compareTo(sum) != 0) {
                            throw new BusinessException("结余回公司拆分合计与审批金额不一致：拆分 ¥"
                                    + sum.toPlainString() + "，审批 ¥" + approvalAmt.toPlainString());
                        }
                    } else {
                        // 旧单只有总额：按 预留→非分成→待分成 依次扣，绝不超过审批金额
                        if (approvalAmt == null || approvalAmt.compareTo(BigDecimal.ZERO) <= 0) {
                            throw new BusinessException("结余回公司金额无效");
                        }
                        FinProjectAccount account = projectAccountService.getOrCreate(approval.getProjectId());
                        BigDecimal remain = approvalAmt;
                        h = remain.min(nz(account.getReserveHeld())).setScale(2, RoundingMode.HALF_UP);
                        remain = remain.subtract(h);
                        n = remain.min(nz(account.getNonShareBalance())).setScale(2, RoundingMode.HALF_UP);
                        remain = remain.subtract(n);
                        s = remain.min(nz(account.getSharePendingBalance())).setScale(2, RoundingMode.HALF_UP);
                        remain = remain.subtract(s);
                        if (remain.compareTo(BigDecimal.ZERO) > 0) {
                            throw new BusinessException("结余不足，无法按审批金额 ¥"
                                    + approvalAmt.toPlainString() + " 回公司");
                        }
                        sum = s.add(n).add(h).setScale(2, RoundingMode.HALF_UP);
                    }
                    if (approvalAmt != null && approvalAmt.compareTo(sum) != 0) {
                        throw new BusinessException("结余回公司金额校验失败：动账 ¥"
                                + sum.toPlainString() + " ≠ 审批 ¥" + approvalAmt.toPlainString());
                    }
                    projectAccountService.returnReserveToCompany(
                            approval.getProjectId(),
                            approval.getPoolId(),
                            s, n, h,
                            approval.getId(),
                            approval.getRemark());
                }
            }
            case ApprovalTypes.LEDGER_REGISTER -> effectLedgerRegister(approval, payload);
            case ApprovalTypes.MONTHLY_VERIFY -> effectMonthlyVerify(approval, payload);
            case ApprovalTypes.ROLLBACK -> effectRollback(approval, payload);
            case ApprovalTypes.ASSET_BORROW -> faAssetService.effectBorrow(approval);
            case ApprovalTypes.ASSET_RETURN -> faAssetService.effectReturn(approval);
            case ApprovalTypes.ASSET_TRANSFER -> faAssetService.effectTransfer(approval);
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
        Long parentId = payload.getLong("parentId");
        if (parentId != null) {
            PmProject parent = projectMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException("父项目不存在");
            }
            if (!ProjectScales.isMajorShell(parent)) {
                throw new BusinessException("仅重大项目可创建小项目");
            }
            if (!Objects.equals(parent.getCompanyId(), companyId)) {
                throw new BusinessException("小项目公司必须与重大项目一致");
            }
            project.setParentId(parentId);
            project.setScale(ProjectScales.KEY);
            project.setCompanyId(parent.getCompanyId());
        }
        project.setApproveStatus(1);
        project.setDescription(payload.getStr("description"));
        project.setStartDate(parsePayloadLocalDate(payload, "startDate"));
        project.setEndDate(parsePayloadLocalDate(payload, "endDate"));
        project.setActualEndDate(parsePayloadLocalDate(payload, "actualEndDate"));
        // 创建人记申请人，而不是最后点通过的审批人
        project.setCreateBy(approval.getApplicantId());
        project.setUpdateBy(approval.getApplicantId());
        if (project.getParentId() == null) {
            project.setCompanyId(companyId);
        }
        Long ownerId = project.getOwnerId();
        Long ownerCompanyId = project.getCompanyId() != null ? project.getCompanyId() : companyId;
        if (ownerId != null
                && !dataScopeService.isGlobalAdmin(ownerId)
                && !dataScopeService.visibleCompanyIds(ownerId).contains(ownerCompanyId)) {
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
        // 重大外壳不创建可动账账户占位也可，但历史兼容仍建；动账由 assertMutable 拦截
        if (!ProjectScales.isMajorShell(project)) {
            projectAccountService.getOrCreate(project.getId());
            if (project.getReserveAmount() != null && project.getReserveAmount().compareTo(BigDecimal.ZERO) > 0) {
                var account = projectAccountService.getOrCreate(project.getId());
                account.setReserveAmount(project.getReserveAmount());
                projectAccountService.updateById(account);
            }
        } else {
            projectAccountService.getOrCreate(project.getId());
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
        projectService.recordFlow(project.getId(), "CREATE", null, ProjectScales.label(project.getScale()),
                approval.getId(),
                project.getParentId() != null
                        ? "创建小项目（" + ProjectScales.label(project.getScale()) + "）"
                        : "创建项目（" + ProjectScales.label(project.getScale()) + "）",
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
        // 先关掉其他待审（当前单已是 APPROVED，不会被关掉），再删项目与任务
        cancelPendingByProject(approval.getProjectId(), "项目已删除，审批自动关闭");
        projectService.deleteProject(approval.getProjectId());
    }

    private void effectPersonalReimburse(WfApproval approval) {
        if (approval.getAmount() == null || approval.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        // 确认到账：只扣公司总账；线下打到申请人收款账户，系统内不进个人钱包
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
        writeSimpleLedger("REIMBURSE", "POOL", pool.getId(), approval.getApplicantId(),
                amount.negate(), poolBefore, pool.getBalance(), null, null, approval.getId(),
                "个人报销扣款", approval.getRemark());
    }

    private void effectWalletWithdraw(WfApproval approval, JSONObject payload) {
        if (approval.getAmount() == null || approval.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal amount = approval.getAmount().setScale(2, RoundingMode.HALF_UP);
        boolean withVoucher = isWithdrawWithVoucher(payload);
        BigDecimal tax;
        BigDecimal net;
        String taxMode;
        BigDecimal taxRate = null;
        if (withVoucher) {
            // 有凭证提现：免税；只扣个人钱包，公司余额不变
            tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            net = amount;
            taxMode = "VOUCHER";
        } else {
            // 无凭证：税额仅用于算线下到手，不写入公司资金池
            BigDecimal snapshotTax = resolveWithdrawTaxFromPayload(payload);
            BigDecimal snapshotNet = payload == null ? null : payload.getBigDecimal("net");
            boolean snapshotOk = snapshotTax != null && snapshotNet != null
                    && snapshotTax.compareTo(BigDecimal.ZERO) >= 0
                    && snapshotTax.compareTo(amount) <= 0
                    && snapshotNet.add(snapshotTax).setScale(2, RoundingMode.HALF_UP).compareTo(amount) == 0;
            if (snapshotOk) {
                tax = snapshotTax;
                net = snapshotNet.setScale(2, RoundingMode.HALF_UP);
                taxMode = payload.getStr("taxMode", "FLAT");
                taxRate = payload.getBigDecimal("taxRate");
            } else {
                WithdrawTaxCalcResult calc = walletWithdrawTaxService.calculate(approval.getCompanyId(), amount);
                tax = calc.getTax();
                net = calc.getNet();
                taxMode = calc.getTaxMode();
                taxRate = calc.getTaxRate();
            }
        }
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("提现税额计算异常");
        }

        HrWallet walletBefore = walletService.getOrCreate(approval.getApplicantId());
        BigDecimal wb = walletBefore.getBalance();
        HrWallet walletAfter = walletService.consumeFrozen(approval.getApplicantId(), amount);

        FinPool pool = resolvePool(approval.getPoolId(), approval.getCompanyId());
        String taxRemark = withVoucher
                ? "有凭证免税"
                : ("TIER".equals(taxMode)
                        ? "阶梯计税"
                        : ("税率 " + (taxRate == null ? "-" : taxRate.toPlainString())));
        writeSimpleLedger("WITHDRAW", "WALLET", pool.getId(), approval.getApplicantId(),
                amount.negate(), wb, walletAfter.getBalance(), null, null, approval.getId(),
                "钱包提现扣款", String.format("全额¥%s，税¥%s，到手¥%s（%s）；公司余额不变",
                        amount.toPlainString(), tax.toPlainString(), net.toPlainString(), taxRemark));
    }

    /** 有凭证提现：payload.withVoucher=true 或 taxMode=VOUCHER */
    private boolean isWithdrawWithVoucher(JSONObject payload) {
        if (payload == null) {
            return false;
        }
        if (asBool(payload.get("withVoucher"))) {
            return true;
        }
        String mode = payload.getStr("taxMode");
        return StringUtils.hasText(mode) && "VOUCHER".equalsIgnoreCase(mode.trim());
    }

    private BigDecimal resolveWithdrawTaxFromPayload(JSONObject payload) {
        if (payload != null && payload.get("tax") != null) {
            BigDecimal fromPayload = payload.getBigDecimal("tax");
            if (fromPayload != null && fromPayload.compareTo(BigDecimal.ZERO) >= 0) {
                return fromPayload.setScale(2, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private void applyWithdrawTaxPayload(ApprovalSubmitRequest request, Long companyId) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("请填写提现金额");
        }
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        request.setAmount(amount);
        Map<String, Object> payload = request.getPayload() == null
                ? new HashMap<>()
                : new HashMap<>(request.getPayload());
        boolean withVoucher = asBool(payload.get("withVoucher"))
                || "VOUCHER".equalsIgnoreCase(String.valueOf(payload.getOrDefault("taxMode", "")).trim());
        if (withVoucher) {
            if (request.getVoucherFileIds() == null || request.getVoucherFileIds().isEmpty()) {
                throw new BusinessException("有凭证提现请上传凭证");
            }
            payload.put("withVoucher", true);
            payload.put("taxMode", "VOUCHER");
            payload.put("taxRate", BigDecimal.ZERO);
            payload.put("tax", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            payload.put("net", amount);
            payload.put("gross", amount);
            payload.put("taxBreakdown", List.of());
            payload.put("taxTiers", List.of());
        } else {
            WithdrawTaxCalcResult calc = walletWithdrawTaxService.calculate(companyId, amount);
            payload.put("withVoucher", false);
            payload.put("taxMode", calc.getTaxMode());
            payload.put("taxRate", calc.getTaxRate());
            payload.put("tax", calc.getTax());
            payload.put("net", calc.getNet());
            payload.put("gross", amount);
            payload.put("taxBreakdown", calc.getBreakdown());
            payload.put("taxTiers", calc.getTiers());
        }
        request.setPayload(payload);
    }

    private void releaseWithdrawFreeze(WfApproval approval, Long operatorId, String remark) {
        if (approval == null || !ApprovalTypes.WALLET_WITHDRAW.equals(approval.getType())) {
            return;
        }
        if (approval.getAmount() == null || approval.getAmount().compareTo(BigDecimal.ZERO) <= 0
                || approval.getApplicantId() == null) {
            return;
        }
        try {
            walletService.unfreeze(approval.getApplicantId(), approval.getAmount());
            addLog(approval.getId(), operatorId, "UNFREEZE", remark);
        } catch (BusinessException e) {
            // 已确认动账后 frozen 已消耗，拒绝/撤回路径不会走到；兜底避免解冻失败阻断主流程外的重复调用
            log.warn("提现解冻失败 approvalId={}, err={}", approval.getId(), e.getMessage());
            throw e;
        }
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
            req.setFundType(payload.getStr("fundType"));
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
            BigDecimal fee = BigDecimal.ZERO;
            String feeMode = originPayload.getStr("feeMode");
            BigDecimal feeValue = originPayload.getBigDecimal("feeValue");
            if (StringUtils.hasText(feeMode) && feeValue != null && feeValue.compareTo(BigDecimal.ZERO) > 0) {
                if ("PERCENT".equalsIgnoreCase(feeMode)) {
                    fee = originGross.multiply(feeValue).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                } else if ("FIXED".equalsIgnoreCase(feeMode)) {
                    fee = feeValue.setScale(2, RoundingMode.HALF_UP);
                }
            }
            BigDecimal net = originGross.subtract(fee);
            BigDecimal ratio = originGross.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ONE
                    : amount.divide(originGross, 8, RoundingMode.HALF_UP);
            BigDecimal reverseNet = net.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            Long projectId = originPayload.getLong("projectId", origin.getProjectId());
            Long poolId = originPayload.getLong("poolId", origin.getPoolId());
            // 关联项目入账曾拨入项目：先退回公司池，再回退入账/渠道
            if (projectId != null && reverseNet.compareTo(BigDecimal.ZERO) > 0) {
                String fundType = ProjectFundTypes.normalize(originPayload.getStr("fundType"));
                projectAccountService.reverseProjectFund(
                        projectId, poolId, reverseNet, fundType, approval.getId(),
                        "回退原单 " + origin.getBizNo());
            }
            financeService.reverseIncomeRegister(
                    poolId,
                    originPayload.getLong("channelId"),
                    amount,
                    originGross,
                    feeMode,
                    feeValue,
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

    /** 支出按指定资金池校验；默认待分成 */
    private void assertExpenseWithinQuota(Long projectId, BigDecimal addAmount) {
        assertExpenseWithinQuota(projectId, addAmount, ProjectFundTypes.SHARE_PENDING);
    }

    private void assertExpenseWithinQuota(Long projectId, BigDecimal addAmount, String fundType) {
        var account = projectAccountService.getOrCreate(projectId);
        String pool = ProjectFundTypes.normalize(fundType);
        BigDecimal bucket = ProjectFundTypes.NON_SHARE.equals(pool)
                ? nz(account.getNonShareBalance())
                : nz(account.getSharePendingBalance());
        if (nz(addAmount).compareTo(bucket) > 0) {
            throw new BusinessException("超过" + ProjectFundTypes.label(pool) + "余额 ¥" + bucket.toPlainString());
        }
    }

    /** 未写 fundType 时按余额自动选池，避免公司预支进非分成后月薪/旧单默认待分成直接失败 */
    private String resolveExpenseFundType(Long projectId, BigDecimal amount, String rawFundType) {
        if (StringUtils.hasText(rawFundType) && !"null".equalsIgnoreCase(rawFundType.trim())) {
            return ProjectFundTypes.normalize(rawFundType);
        }
        var account = projectAccountService.getOrCreate(projectId);
        return ProjectFundTypes.pickExpensePool(
                account.getSharePendingBalance(), account.getNonShareBalance(), amount);
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
        if (nz(addAmount).compareTo(nz(account.getSharePendingBalance())) > 0) {
            throw new BusinessException("超过待分成余额 ¥" + nz(account.getSharePendingBalance()).toPlainString());
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

    private void effectSharePeriod(WfApproval approval, JSONObject payload) {
        Long projectId = approval.getProjectId();
        if (projectId == null) {
            throw new BusinessException("缺少项目");
        }
        String periodMonth = normalizePeriodMonth(payload.getStr("periodMonth"));
        FinProjectAccount account = projectAccountService.getOrCreate(projectId);
        BigDecimal pending = nz(account.getSharePendingBalance());
        if (pending.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("待分成余额为 0，无法分层");
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        BigDecimal settlePercent = payload.getBigDecimal("settlePercent");
        BigDecimal reservePercent = payload.getBigDecimal("reservePercent");
        if (settlePercent == null) {
            settlePercent = nz(project.getSettlePercent());
        }
        if (reservePercent == null) {
            reservePercent = nz(project.getReservePercent());
        }
        assertSettleReservePercents(settlePercent, reservePercent);

        BigDecimal settleAmt = pending.multiply(settlePercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        // 余数进预留，避免分位悬空
        BigDecimal reserveAmt = pending.subtract(settleAmt);
        if (settleAmt.compareTo(BigDecimal.ZERO) < 0 || reserveAmt.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("分层金额无效");
        }

        Map<Long, BigDecimal> shares = new HashMap<>();
        if (settleAmt.compareTo(BigDecimal.ZERO) > 0) {
            // 按生效时待分成余额与成员比例重算，避免提交到审批期间余额变化导致明细不准
            shares = buildSharesFromMembers(projectId, settleAmt);
            projectAccountService.settleToWallets(
                    projectId, approval.getPoolId(), shares, approval.getId(),
                    "自然月分层 " + periodMonth + " · 分成");
        }
        if (reserveAmt.compareTo(BigDecimal.ZERO) > 0) {
            projectAccountService.holdFromSharePending(
                    projectId, reserveAmt, approval.getId(),
                    "自然月分层 " + periodMonth + " · 预留");
        }
        // 回写快照，便于详情展示
        payload.set("periodMonth", periodMonth);
        payload.set("pendingAmount", pending);
        payload.set("settleAmount", settleAmt);
        payload.set("reserveAmount", reserveAmt);
        payload.set("settlePercent", settlePercent);
        payload.set("reservePercent", reservePercent);
        approval.setPayload(payload.toString());
        approval.setAmount(pending);
        updateById(approval);
    }

    private Map<Long, BigDecimal> buildSharesFromMembers(Long projectId, BigDecimal settleAmt) {
        List<PmProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId));
        if (members == null || members.isEmpty()) {
            throw new BusinessException("请先配置分成人员及比例");
        }
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        List<PmProjectMember> valid = new ArrayList<>();
        for (PmProjectMember m : members) {
            if (m.getUserId() != null && nz(m.getPercent()).compareTo(BigDecimal.ZERO) > 0) {
                valid.add(m);
            }
        }
        if (valid.isEmpty()) {
            throw new BusinessException("请先配置分成人员及比例");
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < valid.size(); i++) {
            PmProjectMember m = valid.get(i);
            BigDecimal part;
            if (i == valid.size() - 1) {
                part = settleAmt.subtract(allocated);
                if (part.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("分成比例合计超过 100%，余数无法分摊");
                }
            } else {
                part = settleAmt.multiply(nz(m.getPercent()))
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                allocated = allocated.add(part);
            }
            if (part.compareTo(BigDecimal.ZERO) > 0) {
                shares.merge(m.getUserId(), part, BigDecimal::add);
            }
        }
        if (shares.isEmpty()) {
            throw new BusinessException("分成人员无效");
        }
        return shares;
    }

    private String normalizePeriodMonth(String raw) {
        if (!StringUtils.hasText(raw) || "null".equalsIgnoreCase(raw.trim())) {
            return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        String text = raw.trim();
        try {
            return YearMonth.parse(text, DateTimeFormatter.ofPattern("yyyy-MM")).toString();
        } catch (DateTimeParseException e) {
            throw new BusinessException("自然月格式须为 yyyy-MM");
        }
    }

    private void assertNoDuplicatePeriodApproval(String type, Long projectId, String periodMonth) {
        if (projectId == null || !StringUtils.hasText(periodMonth)) {
            return;
        }
        List<WfApproval> exists = list(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getType, type)
                .eq(WfApproval::getProjectId, projectId)
                .in(WfApproval::getStatus, List.of("PENDING", "APPROVED", "TIMEOUT_PASS", "ROLLING"))
                .orderByDesc(WfApproval::getId)
                .last("LIMIT 50"));
        for (WfApproval a : exists) {
            if (!StringUtils.hasText(a.getPayload())) {
                continue;
            }
            JSONObject p = JSONUtil.parseObj(a.getPayload());
            if (periodMonth.equals(p.getStr("periodMonth"))) {
                throw new BusinessException("该项目 " + periodMonth + " 已有进行中或已通过的「"
                        + ApprovalTypes.label(type) + "」审批");
            }
        }
    }

    private void creditFundBucketOnAccount(FinProjectAccount account, String fundType, BigDecimal amount) {
        String pool = ProjectFundTypes.normalize(fundType);
        if (ProjectFundTypes.NON_SHARE.equals(pool)) {
            account.setNonShareBalance(nz(account.getNonShareBalance()).add(amount));
        } else {
            account.setSharePendingBalance(nz(account.getSharePendingBalance()).add(amount));
        }
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
        } else if (ApprovalTypes.PROJECT_ADVANCE_RETURN.equals(originType) && origin.getProjectId() != null) {
            JSONObject originPayload = JSONUtil.parseObj(origin.getPayload());
            BigDecimal shareAmt = nz(originPayload.getBigDecimal("sharePendingAmount"));
            BigDecimal nonShareAmt = nz(originPayload.getBigDecimal("nonShareAmount"));
            BigDecimal originTotal = shareAmt.add(nonShareAmt);
            if (originTotal.compareTo(BigDecimal.ZERO) <= 0) {
                // 旧单：整笔回非分成
                projectAccountService.creditProjectFund(
                        origin.getProjectId(), origin.getPoolId(), amount, ProjectFundTypes.NON_SHARE,
                        approval.getId(), "退回公司回退入项目", "回退原单 " + origin.getBizNo());
            } else {
                BigDecimal ratio = amount.divide(originTotal, 8, RoundingMode.HALF_UP);
                BigDecimal backShare = shareAmt.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                BigDecimal backNonShare = nonShareAmt.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                // 分位差额补到非分成（无非分成则补到待分成），保证合计等于回退金额
                BigDecimal diff = amount.subtract(backShare.add(backNonShare));
                if (nonShareAmt.signum() > 0 || backNonShare.signum() > 0 || shareAmt.signum() <= 0) {
                    backNonShare = backNonShare.add(diff);
                } else {
                    backShare = backShare.add(diff);
                }
                if (backShare.signum() < 0) {
                    backNonShare = backNonShare.add(backShare);
                    backShare = BigDecimal.ZERO;
                }
                if (backNonShare.signum() < 0) {
                    backShare = backShare.add(backNonShare);
                    backNonShare = BigDecimal.ZERO;
                }
                if (backShare.signum() > 0) {
                    projectAccountService.creditProjectFund(
                            origin.getProjectId(), origin.getPoolId(), backShare, ProjectFundTypes.SHARE_PENDING,
                            approval.getId(), "退回公司回退入项目·待分成", "回退原单 " + origin.getBizNo());
                }
                if (backNonShare.signum() > 0) {
                    projectAccountService.creditProjectFund(
                            origin.getProjectId(), origin.getPoolId(), backNonShare, ProjectFundTypes.NON_SHARE,
                            approval.getId(), "退回公司回退入项目·非分成", "回退原单 " + origin.getBizNo());
                }
            }
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
            creditFundBucketOnAccount(account, ProjectFundTypes.SHARE_PENDING, amount);
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
            // 反向：公司总账加回；仅当原单曾入过钱包时才扣回钱包（兼容旧单）
            FinPool pool = resolvePool(origin.getPoolId(), origin.getCompanyId());
            BigDecimal poolBefore = pool.getBalance();
            creditPoolDirect(pool, amount);
            pool = poolMapper.selectById(pool.getId());

            boolean creditedWallet = origin.getApplicantId() != null
                    && ledgerMapper.selectCount(new LambdaQueryWrapper<FinLedger>()
                    .eq(FinLedger::getApprovalId, origin.getId())
                    .eq(FinLedger::getAccountType, "WALLET")
                    .eq(FinLedger::getUserId, origin.getApplicantId())
                    .gt(FinLedger::getAmount, BigDecimal.ZERO)) > 0;

            Long relatedLedgerId = null;
            if (creditedWallet) {
                HrWallet walletBefore = walletService.getOrCreate(origin.getApplicantId());
                if (nz(walletBefore.getBalance()).compareTo(amount) < 0) {
                    throw new BusinessException("申请人钱包余额不足，无法回退");
                }
                BigDecimal wb = walletBefore.getBalance();
                HrWallet walletAfter = walletService.changeBalance(origin.getApplicantId(), amount.negate());
                relatedLedgerId = writeSimpleLedger("ROLLBACK", "WALLET", pool.getId(), origin.getApplicantId(),
                        amount.negate(), wb, walletAfter.getBalance(), null, null, approval.getId(),
                        "个人报销回退扣款", "回退原单 " + origin.getBizNo());
            }
            writeSimpleLedger("ROLLBACK", "POOL", pool.getId(), origin.getApplicantId(),
                    amount, poolBefore, pool.getBalance(), null, relatedLedgerId, approval.getId(),
                    "个人报销回退入公司", "回退原单 " + origin.getBizNo());
        } else if (ApprovalTypes.WALLET_WITHDRAW.equals(originType)) {
            // 方案 A：提现只动钱包；回退只加回钱包（公司余额本就未因提现变动）
            HrWallet walletBefore = walletService.getOrCreate(origin.getApplicantId());
            BigDecimal wb = walletBefore.getBalance();
            HrWallet walletAfter = walletService.changeBalance(origin.getApplicantId(), amount);
            FinPool pool = resolvePool(origin.getPoolId(), origin.getCompanyId());
            writeSimpleLedger("ROLLBACK", "WALLET", pool.getId(), origin.getApplicantId(),
                    amount, wb, walletAfter.getBalance(), null, null, approval.getId(),
                    "提现回退入钱包", "回退原单 " + origin.getBizNo());
        } else if (List.of(ApprovalTypes.REIMBURSE_PROJECT, ApprovalTypes.SALARY_APPLY,
                ApprovalTypes.PROJECT_BALANCE_APPLY).contains(originType)) {
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
            JSONObject originPayload = JSONUtil.parseObj(origin.getPayload());
            creditFundBucketOnAccount(account, originPayload.getStr("fundType"), amount);
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
        } else if (ApprovalTypes.DIRECT_PAYOUT.equals(originType)) {
            effectDirectPayoutRollback(approval, origin, amount);
        } else if (ApprovalTypes.RESERVE_RETURN.equals(originType) && origin.getProjectId() != null) {
            // 结余回公司回退：公司总账扣回，按原单拆分还原项目池/预留
            JSONObject originPayload = JSONUtil.parseObj(origin.getPayload());
            BigDecimal shareAmt = nz(originPayload.getBigDecimal("sharePendingAmount"));
            BigDecimal nonShareAmt = nz(originPayload.getBigDecimal("nonShareAmount"));
            BigDecimal heldAmt = nz(originPayload.getBigDecimal("reserveHeldAmount"));
            BigDecimal originTotal = shareAmt.add(nonShareAmt).add(heldAmt).setScale(2, RoundingMode.HALF_UP);
            BigDecimal backShare;
            BigDecimal backNonShare;
            BigDecimal backHeld;
            if (originTotal.compareTo(BigDecimal.ZERO) <= 0) {
                if (StringUtils.hasText(originPayload.getStr("periodMonth"))) {
                    // 自然月预留回笼：还原为预留占用
                    backShare = BigDecimal.ZERO;
                    backNonShare = BigDecimal.ZERO;
                    backHeld = amount.setScale(2, RoundingMode.HALF_UP);
                } else {
                    // 旧整笔无拆分：还原到非分成可用，避免误进预留占用
                    backShare = BigDecimal.ZERO;
                    backNonShare = amount.setScale(2, RoundingMode.HALF_UP);
                    backHeld = BigDecimal.ZERO;
                }
            } else {
                BigDecimal ratio = amount.divide(originTotal, 8, RoundingMode.HALF_UP);
                backShare = shareAmt.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                backNonShare = nonShareAmt.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                backHeld = heldAmt.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                BigDecimal diff = amount.subtract(backShare.add(backNonShare).add(backHeld));
                // 分位差额优先补到非分成，其次待分成，再次预留
                if (diff.compareTo(BigDecimal.ZERO) != 0) {
                    if (nonShareAmt.signum() > 0 || backNonShare.signum() > 0) {
                        backNonShare = backNonShare.add(diff);
                    } else if (shareAmt.signum() > 0 || backShare.signum() > 0) {
                        backShare = backShare.add(diff);
                    } else {
                        backHeld = backHeld.add(diff);
                    }
                }
            }
            if (backShare.signum() < 0 || backNonShare.signum() < 0 || backHeld.signum() < 0) {
                throw new BusinessException("结余回公司回退拆分无效");
            }
            FinPool pool = resolvePool(origin.getPoolId(), origin.getCompanyId());
            BigDecimal poolBefore = pool.getBalance();
            boolean ok = new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(poolMapper)
                    .eq(FinPool::getId, pool.getId())
                    .ge(FinPool::getBalance, amount)
                    .setSql("balance = balance - " + amount.toPlainString())
                    .update();
            if (!ok) {
                throw new BusinessException("公司总账余额不足，无法回退结余回公司");
            }
            pool = poolMapper.selectById(pool.getId());
            FinProjectAccount account = projectAccountService.getOrCreate(origin.getProjectId());
            BigDecimal projectBefore = nz(account.getBalance());
            BigDecimal availableBack = backShare.add(backNonShare);
            if (availableBack.signum() > 0) {
                account.setBalance(projectBefore.add(availableBack));
                if (backShare.signum() > 0) {
                    creditFundBucketOnAccount(account, ProjectFundTypes.SHARE_PENDING, backShare);
                }
                if (backNonShare.signum() > 0) {
                    creditFundBucketOnAccount(account, ProjectFundTypes.NON_SHARE, backNonShare);
                }
            }
            if (backHeld.signum() > 0) {
                account.setReserveHeld(nz(account.getReserveHeld()).add(backHeld));
            }
            account.setAdvanceAmount(nz(account.getAdvanceAmount()).add(amount));
            projectAccountService.updateById(account);
            Long poolLedger = writeSimpleLedger("ROLLBACK", "POOL", pool.getId(), null, amount.negate(),
                    poolBefore, pool.getBalance(), origin.getProjectId(), null, approval.getId(),
                    "结余回公司回退扣公司", "回退原单 " + origin.getBizNo());
            writeSimpleLedger("ROLLBACK", "PROJECT", pool.getId(), null, amount,
                    projectBefore, account.getBalance(), origin.getProjectId(), poolLedger, approval.getId(),
                    "结余回公司回退入项目", "回退原单 " + origin.getBizNo()
                            + " · 待分成¥" + backShare.toPlainString()
                            + " 非分成¥" + backNonShare.toPlainString()
                            + " 预留¥" + backHeld.toPlainString());
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
            throw new BusinessException("工资审批缺少收款人");
        }
        var items = payload.getJSONArray("items");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("工资明细为空");
        }
        boolean weekly = "WEEKLY".equalsIgnoreCase(payload.getStr("cycleType"));
        String salaryLabel = weekly ? "周度工资" : "月度工资";
        for (int i = 0; i < items.size(); i++) {
            JSONObject row = items.getJSONObject(i);
            Long projectId = row.getLong("projectId");
            BigDecimal amount = row.getBigDecimal("amount");
            if (amount == null && row.get("amount") != null) {
                amount = new BigDecimal(String.valueOf(row.get("amount")));
            }
            if (projectId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("工资明细无效");
            }
            String fundType = resolveExpenseFundType(projectId, amount, row.getStr("fundType"));
            row.set("fundType", fundType);
            assertExpenseWithinQuota(projectId, amount, fundType);
            String projectName = row.getStr("projectName", "#" + projectId);
            projectAccountService.expenseToWallet(
                    projectId,
                    userId,
                    amount,
                    fundType,
                    approval.getId(),
                    "SALARY",
                    salaryLabel + " · " + projectName,
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
            creditFundBucketOnAccount(account, row.getStr("fundType"), amount);
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
        if (!List.of(ApprovalTypes.SALARY_APPLY, ApprovalTypes.REIMBURSE_PROJECT,
                        ApprovalTypes.REIMBURSE_PERSONAL, ApprovalTypes.WALLET_WITHDRAW)
                .contains(type)) {
            return;
        }
        Map<String, Object> payload = request.getPayload() == null
                ? new HashMap<>()
                : new HashMap<>(request.getPayload());
        Object rawId = payload.get("payMethodId");
        if (rawId == null || !StringUtils.hasText(String.valueOf(rawId)) || "null".equalsIgnoreCase(String.valueOf(rawId))) {
            throw new BusinessException("请选择收款方式，便于财务线下打款");
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
        if (ApprovalTypes.PROJECT_DELETE.equals(type) && request.getProjectId() != null) {
            projectService.assertNoUndeletedChildren(request.getProjectId());
        }
        if (List.of(ApprovalTypes.PROJECT_ADVANCE, ApprovalTypes.PROJECT_ADVANCE_RETURN,
                ApprovalTypes.REIMBURSE_PROJECT,
                ApprovalTypes.SALARY_APPLY, ApprovalTypes.PROJECT_BALANCE_APPLY,
                ApprovalTypes.PROJECT_SETTLE, ApprovalTypes.PROJECT_SHARE_PERIOD,
                ApprovalTypes.RESERVE_RETURN, ApprovalTypes.SHARE_CONFIG).contains(type)) {
            if (request.getProjectId() == null) {
                throw new BusinessException("请选择项目");
            }
            projectAccountService.assertMutableProject(request.getProjectId());
        }
        // 预留回笼金额取自项目已占用预留，提交时可不填金额
        // 退回公司金额可由待分成/非分成组合算出，在专用校验里汇总
        if (List.of(ApprovalTypes.PROJECT_ADVANCE,
                ApprovalTypes.REIMBURSE_PROJECT,
                ApprovalTypes.SALARY_APPLY, ApprovalTypes.PROJECT_BALANCE_APPLY,
                ApprovalTypes.PROJECT_SETTLE).contains(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写金额");
            }
        }
        if (List.of(ApprovalTypes.REIMBURSE_PROJECT, ApprovalTypes.SALARY_APPLY,
                ApprovalTypes.PROJECT_BALANCE_APPLY).contains(type)) {
            Map<String, Object> payload = request.getPayload();
            if (payload == null) {
                payload = new HashMap<>();
                request.setPayload(payload);
            }
            Object rawFund = payload.get("fundType");
            String fundType = ProjectFundTypes.normalize(rawFund == null ? null : String.valueOf(rawFund));
            payload.put("fundType", fundType);
            assertExpenseWithinQuota(request.getProjectId(), request.getAmount(), fundType);
        }
        if (ApprovalTypes.PROJECT_ADVANCE.equals(type)) {
            BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
            request.setAmount(amount);
            Long poolId = request.getPoolId();
            Long companyId = null;
            if (request.getProjectId() != null) {
                PmProject project = projectMapper.selectById(request.getProjectId());
                if (project != null) {
                    companyId = project.getCompanyId();
                    if (poolId == null) {
                        poolId = project.getPoolId();
                    }
                }
            }
            FinPool pool = resolvePool(poolId, companyId);
            if (pool.getStatus() != null && pool.getStatus() == 0) {
                throw new BusinessException("公司账户已禁用，无法转入");
            }
            request.setPoolId(pool.getId());
            BigDecimal poolBal = nz(pool.getBalance());
            if (amount.compareTo(poolBal) > 0) {
                throw new BusinessException("不能超过公司余额 ¥" + poolBal.toPlainString());
            }
        }
        if (ApprovalTypes.PROJECT_ADVANCE_RETURN.equals(type)) {
            FinProjectAccount account = projectAccountService.getOrCreate(request.getProjectId());
            BigDecimal advanceHeld = nz(account.getAdvanceAmount());
            BigDecimal pendingBal = nz(account.getSharePendingBalance());
            BigDecimal nonShareBal = nz(account.getNonShareBalance());

            Map<String, Object> payload = request.getPayload();
            if (payload == null) {
                payload = new java.util.HashMap<>();
                request.setPayload(payload);
            }
            BigDecimal shareAmt = nz(toBd(payload.get("sharePendingAmount"))).setScale(2, RoundingMode.HALF_UP);
            BigDecimal nonShareAmt = nz(toBd(payload.get("nonShareAmount"))).setScale(2, RoundingMode.HALF_UP);
            // 旧单/未拆池：整笔按非分成
            if (shareAmt.signum() <= 0 && nonShareAmt.signum() <= 0) {
                if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("请填写退回金额");
                }
                nonShareAmt = request.getAmount().setScale(2, RoundingMode.HALF_UP);
            }
            if (shareAmt.signum() < 0 || nonShareAmt.signum() < 0) {
                throw new BusinessException("退回金额不能为负");
            }
            BigDecimal total = shareAmt.add(nonShareAmt).setScale(2, RoundingMode.HALF_UP);
            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请至少填写一笔退回金额");
            }
            if (shareAmt.compareTo(pendingBal) > 0) {
                throw new BusinessException("待分成退回不能超过余额 ¥" + pendingBal.toPlainString());
            }
            if (nonShareAmt.compareTo(nonShareBal) > 0) {
                throw new BusinessException("非分成退回不能超过余额 ¥" + nonShareBal.toPlainString());
            }
            if (total.compareTo(advanceHeld) > 0) {
                throw new BusinessException("不能超过公司预支未退回 ¥" + advanceHeld.toPlainString());
            }
            request.setAmount(total);
            payload.put("sharePendingAmount", shareAmt);
            payload.put("nonShareAmount", nonShareAmt);

            Long poolId = request.getPoolId();
            Long companyId = null;
            PmProject project = projectMapper.selectById(request.getProjectId());
            if (project != null) {
                companyId = project.getCompanyId();
                if (poolId == null) {
                    poolId = project.getPoolId();
                }
            }
            FinPool pool = resolvePool(poolId, companyId);
            request.setPoolId(pool.getId());
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
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> row)) {
                    throw new BusinessException("月度工资明细无效");
                }
                Object projectObj = row.get("projectId");
                Object amountObj = row.get("amount");
                Long projectId = projectObj instanceof Number n ? n.longValue() : null;
                BigDecimal amount = toBd(amountObj);
                if (projectId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("月度工资明细无效");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> writable = (Map<String, Object>) row;
                String fundType = resolveExpenseFundType(projectId, amount,
                        writable.get("fundType") == null ? null : String.valueOf(writable.get("fundType")));
                writable.put("fundType", fundType);
                assertExpenseWithinQuota(projectId, amount, fundType);
            }
        }
        if (ApprovalTypes.REIMBURSE_PERSONAL.equals(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写报销金额");
            }
        }
        if (ApprovalTypes.WALLET_WITHDRAW.equals(type)) {
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写提现金额");
            }
            BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
            request.setAmount(amount);
            HrWallet wallet = walletService.getOrCreate(applicantId);
            BigDecimal available = nz(wallet.getAvailable());
            if (available.compareTo(amount) < 0) {
                throw new BusinessException("可用余额不足，当前可用 ¥" + available.toPlainString());
            }
            Map<String, Object> payload = request.getPayload();
            boolean withVoucher = payload != null && (asBool(payload.get("withVoucher"))
                    || "VOUCHER".equalsIgnoreCase(String.valueOf(payload.getOrDefault("taxMode", "")).trim()));
            if (withVoucher && (request.getVoucherFileIds() == null || request.getVoucherFileIds().isEmpty())) {
                throw new BusinessException("有凭证提现请上传凭证");
            }
            // 税额在 resolveApprovalCompanyId 之后由 applyWithdrawTaxPayload 写入
        }
        if (ApprovalTypes.PROJECT_BALANCE_APPLY.equals(type)) {
            // 与个人中心候选列表同源：申请人须可见该项目，防止仅凭公司权限冒领任意项目结余
            projectService.assertCanView(request.getProjectId(), applicantId);
            BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
            request.setAmount(amount);
            Map<String, Object> payload = request.getPayload();
            if (payload == null) {
                payload = new HashMap<>();
                request.setPayload(payload);
            }
            String fundType = resolveExpenseFundType(request.getProjectId(), amount,
                    payload.get("fundType") == null ? null : String.valueOf(payload.get("fundType")));
            payload.put("fundType", fundType);
            assertExpenseWithinQuota(request.getProjectId(), amount, fundType);
        }
        if (ApprovalTypes.DIRECT_PAYOUT.equals(type)) {
            validateDirectPayoutSubmit(request, applicantId);
        }
        if (ApprovalTypes.LEAVE_APPLY.equals(type)) {
            validateLeaveApplySubmit(request, applicantId);
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
            Long projectId = request.getProjectId();
            if (projectId == null && payload != null && payload.get("projectId") != null) {
                Object raw = payload.get("projectId");
                if (raw instanceof Number n) {
                    projectId = n.longValue();
                }
            }
            if ("INCOME".equals(bizType) && projectId != null) {
                Object rawFund = payload == null ? null : payload.get("fundType");
                String fundType = ProjectFundTypes.require(rawFund == null ? null : String.valueOf(rawFund));
                payload.put("fundType", fundType);
            } else if (payload != null && payload.get("fundType") != null
                    && StringUtils.hasText(String.valueOf(payload.get("fundType")))
                    && !"null".equalsIgnoreCase(String.valueOf(payload.get("fundType")))) {
                if (projectId == null) {
                    throw new BusinessException("未关联项目时不能指定资金类型");
                }
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
            Map<String, Object> payload = request.getPayload();
            if (payload == null) {
                payload = new HashMap<>();
                request.setPayload(payload);
            }
            Object monthObj = payload.get("periodMonth");
            if (monthObj != null && StringUtils.hasText(String.valueOf(monthObj))
                    && !"null".equalsIgnoreCase(String.valueOf(monthObj).trim())) {
                String periodMonth = normalizePeriodMonth(String.valueOf(monthObj));
                payload.put("periodMonth", periodMonth);
                assertNoDuplicatePeriodApproval(ApprovalTypes.RESERVE_RETURN, request.getProjectId(), periodMonth);
                FinProjectAccount account = projectAccountService.getOrCreate(request.getProjectId());
                if (nz(account.getReserveHeld()).compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("当前没有可回公司的预留占用");
                }
                request.setAmount(nz(account.getReserveHeld()));
            } else {
                FinProjectAccount account = projectAccountService.getOrCreate(request.getProjectId());
                BigDecimal pendingBal = nz(account.getSharePendingBalance());
                BigDecimal nonShareBal = nz(account.getNonShareBalance());
                BigDecimal heldBal = nz(account.getReserveHeld());
                boolean hasSplit = payload.containsKey("sharePendingAmount")
                        || payload.containsKey("nonShareAmount")
                        || payload.containsKey("reserveHeldAmount");
                if (!hasSplit) {
                    throw new BusinessException("请指定待分成/非分成/预留回公司金额");
                }
                BigDecimal shareAmt = nz(toBd(payload.get("sharePendingAmount"))).setScale(2, RoundingMode.HALF_UP);
                BigDecimal nonShareAmt = nz(toBd(payload.get("nonShareAmount"))).setScale(2, RoundingMode.HALF_UP);
                BigDecimal heldAmt = nz(toBd(payload.get("reserveHeldAmount"))).setScale(2, RoundingMode.HALF_UP);
                if (shareAmt.signum() < 0 || nonShareAmt.signum() < 0 || heldAmt.signum() < 0) {
                    throw new BusinessException("回公司金额不能为负");
                }
                BigDecimal total = shareAmt.add(nonShareAmt).add(heldAmt).setScale(2, RoundingMode.HALF_UP);
                if (total.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("请至少填写一笔回公司金额");
                }
                // 防前端未失焦仍带着旧值：若显式传了 amount，须与拆分合计一致
                if (request.getAmount() != null
                        && request.getAmount().setScale(2, RoundingMode.HALF_UP).compareTo(total) != 0) {
                    throw new BusinessException("回公司合计 ¥" + total.toPlainString()
                            + " 与提交金额 ¥"
                            + request.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                            + " 不一致，请重新填写后再提交");
                }
                if (shareAmt.compareTo(pendingBal) > 0) {
                    throw new BusinessException("待分成回公司不能超过余额 ¥" + pendingBal.toPlainString());
                }
                if (nonShareAmt.compareTo(nonShareBal) > 0) {
                    throw new BusinessException("非分成回公司不能超过余额 ¥" + nonShareBal.toPlainString());
                }
                if (heldAmt.compareTo(heldBal) > 0) {
                    throw new BusinessException("预留占用回公司不能超过 ¥" + heldBal.toPlainString());
                }
                request.setAmount(total);
                payload.put("sharePendingAmount", shareAmt);
                payload.put("nonShareAmount", nonShareAmt);
                payload.put("reserveHeldAmount", heldAmt);
            }
            // 确保有资金池
            if (request.getPoolId() == null) {
                PmProject project = projectMapper.selectById(request.getProjectId());
                if (project != null) {
                    request.setPoolId(project.getPoolId());
                }
            }
        }
        if (ApprovalTypes.PROJECT_SHARE_PERIOD.equals(type)) {
            if (request.getProjectId() == null) {
                throw new BusinessException("请选择项目");
            }
            Map<String, Object> payload = request.getPayload();
            if (payload == null) {
                payload = new HashMap<>();
                request.setPayload(payload);
            }
            String periodMonth = normalizePeriodMonth(
                    payload.get("periodMonth") == null ? null : String.valueOf(payload.get("periodMonth")));
            payload.put("periodMonth", periodMonth);
            assertNoDuplicatePeriodApproval(ApprovalTypes.PROJECT_SHARE_PERIOD, request.getProjectId(), periodMonth);
            FinProjectAccount account = projectAccountService.getOrCreate(request.getProjectId());
            BigDecimal pending = nz(account.getSharePendingBalance());
            if (pending.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("待分成余额为 0，无法分层");
            }
            request.setAmount(pending);
            PmProject project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new BusinessException("项目不存在");
            }
            if (request.getPoolId() == null) {
                request.setPoolId(project.getPoolId());
            }
            BigDecimal settlePercent = toBd(payload.get("settlePercent"));
            BigDecimal reservePercent = toBd(payload.get("reservePercent"));
            if (settlePercent == null) {
                settlePercent = nz(project.getSettlePercent());
            }
            if (reservePercent == null) {
                reservePercent = nz(project.getReservePercent());
            }
            assertSettleReservePercents(settlePercent, reservePercent);
            payload.put("settlePercent", settlePercent);
            payload.put("reservePercent", reservePercent);
            payload.put("pendingAmount", pending);
            // 预计算分成明细（按成员比例）
            if (payload.get("items") == null && payload.get("shares") == null) {
                BigDecimal settleAmt = pending.multiply(settlePercent)
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                if (settleAmt.compareTo(BigDecimal.ZERO) > 0) {
                    Map<Long, BigDecimal> shares = buildSharesFromMembers(request.getProjectId(), settleAmt);
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("userId", e.getKey());
                        row.put("amount", e.getValue());
                        items.add(row);
                    }
                    payload.put("items", items);
                }
            }
        }
        if (ApprovalTypes.ASSET_BORROW.equals(type) || ApprovalTypes.ASSET_RETURN.equals(type)
                || ApprovalTypes.ASSET_TRANSFER.equals(type)) {
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
            } else if (ApprovalTypes.ASSET_RETURN.equals(type)) {
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
            } else {
                Long toUserId = null;
                Object toObj = payload.get("toUserId");
                if (toObj instanceof Number n) {
                    toUserId = n.longValue();
                } else if (toObj != null) {
                    String text = String.valueOf(toObj).trim();
                    if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
                        try {
                            toUserId = Long.parseLong(text);
                        } catch (NumberFormatException e) {
                            throw new BusinessException("新领用人参数无效");
                        }
                    }
                }
                faAssetService.assertCanTransfer(assetId, applicantId, toUserId);
                var asset = faAssetService.getById(assetId);
                if (asset != null) {
                    request.setAmount(asset.getOriginalValue());
                    request.setCompanyId(asset.getCompanyId());
                    payload.put("assetCode", asset.getAssetCode());
                    payload.put("assetName", asset.getName());
                    payload.put("originalValue", asset.getOriginalValue());
                    payload.put("fromUserId", asset.getHolderUserId());
                    payload.put("toUserId", toUserId);
                    payload.put("companyId", asset.getCompanyId());
                    SysUser from = asset.getHolderUserId() != null ? userService.getById(asset.getHolderUserId()) : null;
                    SysUser to = userService.getById(toUserId);
                    payload.put("fromUserName", from == null ? null
                            : (StringUtils.hasText(from.getNickname()) ? from.getNickname() : from.getUsername()));
                    payload.put("toUserName", to == null ? null
                            : (StringUtils.hasText(to.getNickname()) ? to.getNickname() : to.getUsername()));
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
        if (payload.getLong("payeeUserId") != null) {
            userIds.add(payload.getLong("payeeUserId"));
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
        if (payload.getLong("payeeUserId") != null) {
            data.put("payeeUserName", nameMap.get(payload.getLong("payeeUserId")));
        }
        String sourceType = payload.getStr("sourceType");
        if (StringUtils.hasText(sourceType)) {
            data.put("sourceTypeLabel", switch (sourceType.trim().toUpperCase()) {
                case "COMPANY" -> "公司余额";
                case "PROJECT" -> "项目资金";
                default -> sourceType;
            });
        }
        String fundTypeLabel = payload.getStr("fundType");
        if (StringUtils.hasText(fundTypeLabel)) {
            data.put("fundTypeLabel", ProjectFundTypes.label(ProjectFundTypes.normalize(fundTypeLabel)));
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
                && (!ApprovalTypes.needMoneyConfirm(a.getType())
                || Integer.valueOf(3).equals(a.getConfirmStatus()))
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
        if (poolId != null) {
            FinPool pool = poolMapper.selectById(poolId);
            if (pool != null) {
                ledger.setCompanyId(pool.getCompanyId());
            }
        }
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

    private void validateLeaveApplySubmit(ApprovalSubmitRequest request, long applicantId) {
        Map<String, Object> payload = request.getPayload();
        if (payload == null) {
            throw new BusinessException("请填写请假信息");
        }
        LocalDate start = parseIsoDate(payload.get("startDate"));
        LocalDate end = parseIsoDate(payload.get("endDate"));
        if (start == null || end == null) {
            throw new BusinessException("请选择请假起止日期");
        }
        if (end.isBefore(start)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (start.plusDays(60).isBefore(end)) {
            throw new BusinessException("单次请假不超过 60 天");
        }
        String reason = payload.get("reason") == null ? request.getRemark() : String.valueOf(payload.get("reason"));
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写请假事由");
        }
        payload.put("startDate", start.toString());
        payload.put("endDate", end.toString());
        payload.put("reason", reason.trim());
        payload.put("leaveDays", start.datesUntil(end.plusDays(1)).count());
        if (!StringUtils.hasText(request.getRemark())) {
            request.setRemark(reason.trim());
        }
        if (request.getCompanyId() == null) {
            throw new BusinessException("请选择所属公司");
        }
        leaveService.assertLeaveRangeAvailable(request.getCompanyId(), applicantId, start, end);
    }

    private LocalDate parseIsoDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        String text = String.valueOf(raw).trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            if (text.length() >= 10) {
                return LocalDate.parse(text.substring(0, 10));
            }
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BusinessException("请假日期格式无效");
        }
    }

    private void effectLeaveApply(WfApproval approval, JSONObject payload) {
        LocalDate start = parseIsoDate(payload.get("startDate"));
        LocalDate end = parseIsoDate(payload.get("endDate"));
        String reason = payload.getStr("reason", approval.getRemark());
        leaveService.effectLeaveApproval(
                approval.getId(),
                approval.getCompanyId(),
                approval.getApplicantId(),
                start,
                end,
                reason);
    }

    private void validateDirectPayoutSubmit(ApprovalSubmitRequest request, long applicantId) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("请填写发钱金额");
        }
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        request.setAmount(amount);
        Map<String, Object> payload = request.getPayload();
        if (payload == null) {
            payload = new HashMap<>();
            request.setPayload(payload);
        }
        Object sourceRaw = payload.get("sourceType");
        String sourceType = sourceRaw == null ? "" : String.valueOf(sourceRaw).trim().toUpperCase();
        if (!List.of("COMPANY", "PROJECT").contains(sourceType)) {
            throw new BusinessException("请选择资金来源：公司余额或项目资金");
        }
        payload.put("sourceType", sourceType);

        Long payeeUserId = null;
        Object payeeRaw = payload.get("payeeUserId");
        if (payeeRaw instanceof Number n) {
            payeeUserId = n.longValue();
        } else if (payeeRaw != null) {
            String text = String.valueOf(payeeRaw).trim();
            if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
                try {
                    payeeUserId = Long.parseLong(text);
                } catch (NumberFormatException e) {
                    throw new BusinessException("收款人参数无效");
                }
            }
        }
        if (payeeUserId == null) {
            throw new BusinessException("请选择收款人");
        }
        SysUser payee = userService.getById(payeeUserId);
        if (payee == null || (payee.getStatus() != null && payee.getStatus() == 0)) {
            throw new BusinessException("收款人不存在或已停用");
        }
        payload.put("payeeUserId", payeeUserId);
        payload.put("payeeUserName", StringUtils.hasText(payee.getNickname()) ? payee.getNickname() : payee.getUsername());

        Long companyIdHint = request.getCompanyId();
        if ("COMPANY".equals(sourceType)) {
            if (request.getPoolId() == null) {
                throw new BusinessException("请选择公司资金池");
            }
            FinPool pool = resolvePool(request.getPoolId(), companyIdHint);
            if (pool.getStatus() != null && pool.getStatus() == 0) {
                throw new BusinessException("公司账户已禁用");
            }
            request.setPoolId(pool.getId());
            request.setProjectId(null);
            payload.remove("fundType");
            BigDecimal poolBal = nz(pool.getBalance());
            if (amount.compareTo(poolBal) > 0) {
                throw new BusinessException("不能超过公司余额 ¥" + poolBal.toPlainString());
            }
            companyIdHint = pool.getCompanyId();
        } else {
            if (request.getProjectId() == null) {
                throw new BusinessException("请选择项目");
            }
            projectAccountService.assertMutableProject(request.getProjectId());
            Object rawFund = payload.get("fundType");
            String fundType = resolveExpenseFundType(request.getProjectId(), amount,
                    rawFund == null ? null : String.valueOf(rawFund));
            payload.put("fundType", fundType);
            assertExpenseWithinQuota(request.getProjectId(), amount, fundType);
            PmProject project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new BusinessException("项目不存在");
            }
            companyIdHint = project.getCompanyId();
            if (request.getPoolId() == null && project.getPoolId() != null) {
                request.setPoolId(project.getPoolId());
            }
        }

        if (companyIdHint == null) {
            throw new BusinessException("无法确定所属公司");
        }
        if (!dataScopeService.isGlobalAdmin(applicantId) && !canFinanceHandle(applicantId, companyIdHint)) {
            throw new BusinessException("仅财务可发起财务发钱");
        }
        request.setCompanyId(companyIdHint);
    }

    private void effectDirectPayout(WfApproval approval, JSONObject payload) {
        Long payeeUserId = payload.getLong("payeeUserId");
        if (payeeUserId == null) {
            throw new BusinessException("财务发钱缺少收款人");
        }
        BigDecimal amount = approval.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("财务发钱金额无效");
        }
        String sourceType = payload.getStr("sourceType", "").trim().toUpperCase();
        String title = StringUtils.hasText(approval.getTitle()) ? approval.getTitle() : "财务发钱";
        if ("COMPANY".equals(sourceType)) {
            Long poolId = approval.getPoolId() != null ? approval.getPoolId() : payload.getLong("poolId");
            if (poolId == null) {
                throw new BusinessException("财务发钱缺少公司资金池");
            }
            financeService.payoutPoolToWallet(poolId, payeeUserId, amount, approval.getId(), title, approval.getRemark());
        } else if ("PROJECT".equals(sourceType)) {
            if (approval.getProjectId() == null) {
                throw new BusinessException("财务发钱缺少项目");
            }
            String fundType = ProjectFundTypes.normalize(payload.getStr("fundType"));
            assertExpenseWithinQuota(approval.getProjectId(), amount, fundType);
            projectAccountService.expenseToWallet(
                    approval.getProjectId(),
                    payeeUserId,
                    amount,
                    fundType,
                    approval.getId(),
                    "PAYOUT",
                    title,
                    approval.getRemark());
        } else {
            throw new BusinessException("财务发钱资金来源无效");
        }
        notificationService.notifyUser(
                payeeUserId,
                "已入账 · " + title,
                "财务发钱 ¥" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + " 已转入你的个人钱包，单号 " + approval.getBizNo(),
                "approval", approval.getId(), "/account");
    }

    private void effectDirectPayoutRollback(WfApproval approval, WfApproval origin, BigDecimal amount) {
        JSONObject originPayload = JSONUtil.parseObj(origin.getPayload());
        Long payeeUserId = originPayload.getLong("payeeUserId");
        if (payeeUserId == null) {
            throw new BusinessException("原单缺少收款人，无法回退");
        }
        String sourceType = originPayload.getStr("sourceType", "").trim().toUpperCase();
        String remark = "回退原单 " + origin.getBizNo();
        if ("COMPANY".equals(sourceType)) {
            Long poolId = origin.getPoolId() != null ? origin.getPoolId() : originPayload.getLong("poolId");
            if (poolId == null) {
                throw new BusinessException("原单缺少资金池，无法回退");
            }
            financeService.reversePayoutPoolFromWallet(
                    poolId, payeeUserId, amount, approval.getId(), "财务发钱回退", remark);
            return;
        }
        if (!"PROJECT".equals(sourceType)) {
            throw new BusinessException("原单资金来源无效，无法回退");
        }
        if (origin.getProjectId() == null) {
            throw new BusinessException("原单缺少项目，无法回退");
        }
        var account = projectAccountService.getOrCreate(origin.getProjectId());
        if (nz(account.getExpenseAmount()).compareTo(amount) < 0) {
            throw new BusinessException("回退金额超过项目累计支出");
        }
        boolean creditedWallet = ledgerMapper.selectCount(new LambdaQueryWrapper<FinLedger>()
                .eq(FinLedger::getApprovalId, origin.getId())
                .eq(FinLedger::getAccountType, "WALLET")
                .eq(FinLedger::getUserId, payeeUserId)
                .gt(FinLedger::getAmount, BigDecimal.ZERO)) > 0;
        BigDecimal before = nz(account.getBalance());
        account.setBalance(before.add(amount));
        account.setExpenseAmount(nz(account.getExpenseAmount()).subtract(amount));
        creditFundBucketOnAccount(account, originPayload.getStr("fundType"), amount);
        projectAccountService.updateById(account);
        Long projectLedger = writeSimpleLedger("ROLLBACK", "PROJECT", origin.getPoolId(), null, amount,
                before, account.getBalance(), origin.getProjectId(), null, approval.getId(),
                "财务发钱回退", remark);
        if (creditedWallet) {
            HrWallet walletBefore = walletService.getOrCreate(payeeUserId);
            if (nz(walletBefore.getBalance()).compareTo(amount) < 0) {
                throw new BusinessException("收款人钱包余额不足，无法回退");
            }
            BigDecimal wb = walletBefore.getBalance();
            HrWallet walletAfter = walletService.changeBalance(payeeUserId, amount.negate());
            writeSimpleLedger("ROLLBACK", "WALLET", origin.getPoolId(), payeeUserId,
                    amount.negate(), wb, walletAfter.getBalance(), origin.getProjectId(), projectLedger, approval.getId(),
                    "财务发钱回退扣个人钱包", remark);
        }
        projectAccountService.assertBalanced(origin.getProjectId());
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
