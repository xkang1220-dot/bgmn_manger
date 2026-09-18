package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.LedgerQuery;
import com.kk.biz.dto.LedgerRegisterResult;
import com.kk.biz.dto.LedgerThresholdSaveRequest;
import com.kk.biz.dto.ManualShareItem;
import com.kk.biz.dto.ProjectManualSettleRequest;
import com.kk.biz.dto.ProjectSettleRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinLedgerThreshold;
import com.kk.biz.entity.FinPayChannel;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.FinProjectAccount;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.entity.SysFile;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.mapper.FinLedgerMapper;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.FinProjectAccountMapper;
import com.kk.biz.mapper.HrWalletMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.mapper.WfApprovalMapper;
import com.kk.biz.service.FinLedgerThresholdService;
import com.kk.biz.service.FinPayChannelService;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.support.BizNoGenerator;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.biz.workflow.ProjectFundTypes;
import com.kk.biz.workflow.ProjectScales;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysNotificationService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FinanceServiceImpl extends ServiceImpl<FinPoolMapper, FinPool> implements FinanceService {

    private final FinLedgerMapper ledgerMapper;
    private final HrWalletMapper walletMapper;
    private final FinProjectAccountMapper projectAccountMapper;
    private final HrWalletService walletService;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final SysFileService fileService;
    private final BizNoGenerator bizNoGenerator;
    private final FinPayChannelService payChannelService;
    private final FinLedgerThresholdService ledgerThresholdService;
    private final SysNotificationService notificationService;
    private final WfApprovalMapper approvalMapper;
    /** 审批动账时带入流水 approvalId（勿改为构造注入） */
    private final ThreadLocal<Long> approvalIdHolder = ThreadLocal.withInitial(() -> null);

    @Lazy
    @Autowired
    private WfApprovalService wfApprovalService;

    @Lazy
    @Autowired
    private FinProjectAccountService projectAccountService;

    @Override
    public FinPool getDefaultPool() {
        Long companyId = resolveLoginCompanyId();
        if (companyId != null) {
            FinPool pool = getOne(new LambdaQueryWrapper<FinPool>()
                    .eq(FinPool::getIsDefault, 1)
                    .eq(FinPool::getCompanyId, companyId)
                    .last("LIMIT 1"));
            if (pool == null) {
                pool = getOne(new LambdaQueryWrapper<FinPool>()
                        .eq(FinPool::getCompanyId, companyId)
                        .orderByAsc(FinPool::getId)
                        .last("LIMIT 1"));
            }
            if (pool != null) {
                return pool;
            }
            throw new BusinessException("当前公司未配置资金池");
        }
        if (isLoginGlobalAdmin()) {
            throw new BusinessException("请指定资金池（管理员未绑定主部门公司时不可自动选池）");
        }
        throw new BusinessException("无法确定所属公司，请先配置部门");
    }

    @Override
    public List<FinPool> listVisiblePools() {
        LambdaQueryWrapper<FinPool> wrapper = new LambdaQueryWrapper<FinPool>().orderByAsc(FinPool::getId);
        applyPoolCompanyFilter(wrapper);
        List<FinPool> pools = list(wrapper);
        fillPoolCompanyNames(pools);
        return pools;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createPool(FinPool pool) {
        BigDecimal initial = pool.getBalance() == null ? BigDecimal.ZERO : pool.getBalance();
        if (initial.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("初始余额不能为负数");
        }
        if (pool.getCompanyId() == null) {
            throw new BusinessException("请选择所属公司");
        }
        if (StpUtil.isLogin() && !isLoginGlobalAdmin() && !visibleCompanies().contains(pool.getCompanyId())) {
            throw new BusinessException("不能为其他公司创建资金池");
        }
        // 余额一律经流水变更：先建池为 0，有初始金额再入账留痕
        pool.setBalance(BigDecimal.ZERO);
        if (pool.getStatus() == null) {
            pool.setStatus(1);
        }
        if (Integer.valueOf(1).equals(pool.getIsDefault())) {
            clearDefault(pool.getCompanyId());
        } else if (pool.getIsDefault() == null) {
            pool.setIsDefault(0);
        }
        save(pool);
        if (initial.compareTo(BigDecimal.ZERO) > 0) {
            creditPool(pool, initial);
            FinPool after = getById(pool.getId());
            writeLedger(
                    "INCOME",
                    "POOL",
                    pool.getId(),
                    null,
                    initial,
                    BigDecimal.ZERO,
                    after.getBalance(),
                    null,
                    null,
                    "资金池期初建账：" + pool.getName(),
                    "新建资金池初始余额"
            );
        }
    }

    @Override
    public void updatePool(FinPool pool) {
        // 禁止直接改余额，动账必须走 createLedger / 分钱接口并写流水
        pool.setBalance(null);
        pool.setCompanyId(null);
        FinPool db = getById(pool.getId());
        if (db == null) {
            throw new BusinessException("资金池不存在");
        }
        assertPoolVisible(db);
        if (Integer.valueOf(1).equals(pool.getIsDefault())) {
            clearDefault(db.getCompanyId());
        }
        updateById(pool);
    }

    @Override
    public Page<FinLedger> pageLedger(LedgerQuery query) {
        if (query == null) {
            query = new LedgerQuery();
        }
        LambdaQueryWrapper<FinLedger> wrapper = new LambdaQueryWrapper<FinLedger>()
                .eq(StringUtils.hasText(query.getBizType()), FinLedger::getBizType, query.getBizType())
                .eq(StringUtils.hasText(query.getAccountType()), FinLedger::getAccountType, query.getAccountType())
                .eq(query.getUserId() != null, FinLedger::getUserId, query.getUserId())
                .eq(query.getCompanyId() != null, FinLedger::getCompanyId, query.getCompanyId())
                .eq(query.getPoolId() != null, FinLedger::getPoolId, query.getPoolId())
                .eq(query.getChannelId() != null, FinLedger::getChannelId, query.getChannelId())
                .ge(query.getStartTime() != null, FinLedger::getOccurTime, query.getStartTime())
                .le(query.getEndTime() != null, FinLedger::getOccurTime, query.getEndTime())
                .orderByDesc(FinLedger::getOccurTime)
                .orderByDesc(FinLedger::getId);
        applyProjectIdFilter(wrapper, query.getProjectId());
        if (query.getMinAmount() != null) {
            wrapper.apply("ABS(amount) >= {0}", query.getMinAmount());
        }
        if (query.getMaxAmount() != null) {
            wrapper.apply("ABS(amount) <= {0}", query.getMaxAmount());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(FinLedger::getBizNo, kw)
                    .or().like(FinLedger::getTitle, kw)
                    .or().like(FinLedger::getRemark, kw));
        }
        if (!query.isSkipCompanyScope()) {
            applyCompanyFilter(wrapper);
        }
        Page<FinLedger> result = ledgerMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        fillLedgers(result.getRecords());
        return result;
    }

    @Override
    public Map<String, Object> myWalletBoard(Long userId, String period) {
        if (userId == null) {
            throw new BusinessException("缺少用户");
        }
        boolean monthly = "monthly".equalsIgnoreCase(period) || "month".equalsIgnoreCase(period);
        HrWallet wallet = walletService.getOrCreate(userId);

        LocalDate today = LocalDate.now();
        LocalDateTime rangeStart = monthly
                ? YearMonth.from(today).minusMonths(11).atDay(1).atStartOfDay()
                : today.minusDays(29).atStartOfDay();

        List<FinLedger> rangeLedgers = ledgerMapper.selectList(new LambdaQueryWrapper<FinLedger>()
                .eq(FinLedger::getAccountType, "WALLET")
                .eq(FinLedger::getUserId, userId)
                .ge(FinLedger::getOccurTime, rangeStart)
                .orderByAsc(FinLedger::getOccurTime)
                .orderByAsc(FinLedger::getId));
        fillLedgers(rangeLedgers);

        Map<String, BigDecimal> bucket = new LinkedHashMap<>();
        if (monthly) {
            for (int i = 11; i >= 0; i--) {
                YearMonth ym = YearMonth.from(today).minusMonths(i);
                bucket.put(ym.toString(), BigDecimal.ZERO);
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
            for (FinLedger ledger : rangeLedgers) {
                if (ledger.getOccurTime() == null || ledger.getAmount() == null) {
                    continue;
                }
                String key = ledger.getOccurTime().format(fmt);
                bucket.merge(key, ledger.getAmount(), BigDecimal::add);
            }
        } else {
            for (int i = 29; i >= 0; i--) {
                bucket.put(today.minusDays(i).toString(), BigDecimal.ZERO);
            }
            for (FinLedger ledger : rangeLedgers) {
                if (ledger.getOccurTime() == null || ledger.getAmount() == null) {
                    continue;
                }
                String key = ledger.getOccurTime().toLocalDate().toString();
                bucket.merge(key, ledger.getAmount(), BigDecimal::add);
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : bucket.entrySet()) {
            BigDecimal abs = e.getValue().abs();
            if (abs.compareTo(maxAbs) > 0) {
                maxAbs = abs;
            }
            Map<String, Object> point = new HashMap<>();
            point.put("label", e.getKey());
            point.put("amount", e.getValue());
            trend.add(point);
        }
        for (Map<String, Object> point : trend) {
            BigDecimal amount = (BigDecimal) point.get("amount");
            double pct = maxAbs.compareTo(BigDecimal.ZERO) == 0
                    ? 0
                    : amount.abs().multiply(BigDecimal.valueOf(100))
                    .divide(maxAbs, 2, RoundingMode.HALF_UP).doubleValue();
            point.put("pct", pct);
        }

        Map<String, BigDecimal> byType = new HashMap<>();
        for (FinLedger ledger : rangeLedgers) {
            if (ledger.getAmount() == null || ledger.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String type = StringUtils.hasText(ledger.getBizType()) ? ledger.getBizType() : "OTHER";
            byType.merge(type, ledger.getAmount(), BigDecimal::add);
        }
        List<Map<String, Object>> sourceBreakdown = byType.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("bizType", e.getKey());
                    row.put("amount", e.getValue());
                    return row;
                })
                .collect(Collectors.toList());

        LocalDateTime monthStart = YearMonth.from(today).atDay(1).atStartOfDay();
        LocalDateTime monthEnd = today.atTime(LocalTime.MAX);
        List<FinLedger> monthLedgers = ledgerMapper.selectList(new LambdaQueryWrapper<FinLedger>()
                .eq(FinLedger::getAccountType, "WALLET")
                .eq(FinLedger::getUserId, userId)
                .ge(FinLedger::getOccurTime, monthStart)
                .le(FinLedger::getOccurTime, monthEnd));
        BigDecimal monthIn = BigDecimal.ZERO;
        BigDecimal monthOut = BigDecimal.ZERO;
        for (FinLedger ledger : monthLedgers) {
            BigDecimal amt = ledger.getAmount() == null ? BigDecimal.ZERO : ledger.getAmount();
            if (amt.compareTo(BigDecimal.ZERO) > 0) {
                monthIn = monthIn.add(amt);
            } else if (amt.compareTo(BigDecimal.ZERO) < 0) {
                monthOut = monthOut.add(amt.abs());
            }
        }

        LedgerQuery recentQ = new LedgerQuery();
        recentQ.setPage(1);
        recentQ.setPageSize(8);
        recentQ.setAccountType("WALLET");
        recentQ.setUserId(userId);
        recentQ.setSkipCompanyScope(true);
        Page<FinLedger> recentPage = pageLedger(recentQ);

        long pendingConfirm = approvalMapper.selectCount(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getApplicantId, userId)
                .eq(WfApproval::getConfirmStatus, 2)
                .in(WfApproval::getStatus, "APPROVED", "TIMEOUT_PASS"));

        Map<String, Object> result = new HashMap<>();
        result.put("balance", wallet.getBalance());
        result.put("frozen", wallet.getFrozen());
        result.put("available", wallet.getAvailable());
        result.put("period", monthly ? "monthly" : "daily");
        result.put("trend", trend);
        result.put("sourceBreakdown", sourceBreakdown);
        result.put("recentLedgers", recentPage.getRecords());
        result.put("pendingConfirmCount", pendingConfirm);
        result.put("monthIn", monthIn);
        result.put("monthOut", monthOut);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createLedger(LedgerCreateRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于 0");
        }
        validateLedgerFundType(request);
        try {
            if (request.getApprovalId() != null) {
                approvalIdHolder.set(request.getApprovalId());
            }
            String type = request.getBizType();
            Long ledgerId;
            if ("INCOME".equals(type)) {
                ledgerId = applyIncomeWithFee(request);
            } else if ("EXPENSE".equals(type)) {
                ledgerId = applyChange(request, request.getAmount().negate());
            } else if ("TRANSFER".equals(type)) {
                throw new BusinessException("已取消公司与个人之间的直接划拨，请走项目分钱或公司出账");
            } else {
                throw new BusinessException("不支持的业务类型");
            }
            bindVouchers(request.getVoucherFileIds(), ledgerId);
        } finally {
            approvalIdHolder.remove();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LedgerRegisterResult registerCompanyLedger(LedgerCreateRequest request) {
        if (request == null) {
            throw new BusinessException("请求不能为空");
        }
        if (!"POOL".equals(request.getAccountType())) {
            request.setAccountType("POOL");
        }
        String bizType = request.getBizType() == null ? "" : request.getBizType().trim();
        if (!List.of("INCOME", "EXPENSE").contains(bizType)) {
            throw new BusinessException("仅支持公司入账或出账");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于 0");
        }
        if (request.getPoolId() == null) {
            throw new BusinessException("请选择资金池");
        }
        FinPool pool = getById(request.getPoolId());
        if (pool == null) {
            throw new BusinessException("资金池不存在");
        }
        if (pool.getCompanyId() == null) {
            throw new BusinessException("资金池未绑定公司");
        }
        if ("INCOME".equals(bizType) && request.getChannelId() == null) {
            throw new BusinessException("入账请选择收款渠道");
        }
        validateLedgerFundType(request);

        // 入账：始终财务审批
        if ("INCOME".equals(bizType)) {
            return submitLedgerApproval(request, pool.getCompanyId(), false);
        }

        // 出账：未启用阈值 → 财务审批；启用后按档分流
        FinLedgerThreshold cfg = ledgerThresholdService.findEnabled(pool.getCompanyId());
        if (cfg == null) {
            return submitLedgerApproval(request, pool.getCompanyId(), false);
        }
        BigDecimal amount = request.getAmount();
        BigDecimal notifyLine = cfg.getNotifyThreshold() == null ? BigDecimal.ZERO : cfg.getNotifyThreshold();
        BigDecimal approveLine = cfg.getApproveThreshold() == null ? BigDecimal.ZERO : cfg.getApproveThreshold();

        if (amount.compareTo(approveLine) >= 0) {
            return submitLedgerApproval(request, pool.getCompanyId(), true);
        }

        createLedger(request);
        if (amount.compareTo(notifyLine) >= 0) {
            boolean notified = notifyShareholdersExpense(pool.getCompanyId(), amount, request.getTitle(), request.getRemark());
            if (notified) {
                return LedgerRegisterResult.direct("已出账，并已通知该公司股东");
            }
            return LedgerRegisterResult.direct("已出账（该公司暂无其他股东可通知）");
        }
        return LedgerRegisterResult.direct("已直接出账（未达通知线）");
    }

    @Override
    public FinLedgerThreshold getLedgerThreshold(Long companyId) {
        return ledgerThresholdService.getOrDefault(companyId);
    }

    @Override
    public void saveLedgerThreshold(LedgerThresholdSaveRequest request) {
        ledgerThresholdService.save(request);
    }

    private LedgerRegisterResult submitLedgerApproval(LedgerCreateRequest request, Long companyId, boolean shareholderAll) {
        ApprovalSubmitRequest submit = new ApprovalSubmitRequest();
        submit.setType(ApprovalTypes.LEDGER_REGISTER);
        String typeLabel = "INCOME".equals(request.getBizType()) ? "入账" : "出账";
        submit.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle() : ("总账" + typeLabel));
        submit.setAmount(request.getAmount());
        submit.setPoolId(request.getPoolId());
        submit.setProjectId(request.getProjectId());
        submit.setCompanyId(companyId);
        submit.setRemark(request.getRemark());
        submit.setVoucherFileIds(request.getVoucherFileIds());
        Map<String, Object> payload = new HashMap<>();
        payload.put("bizType", request.getBizType());
        payload.put("accountType", "POOL");
        payload.put("poolId", request.getPoolId());
        payload.put("channelId", request.getChannelId());
        payload.put("projectId", request.getProjectId());
        payload.put("amount", request.getAmount());
        payload.put("feeMode", request.getFeeMode());
        payload.put("feeValue", request.getFeeValue());
        payload.put("fundType", request.getFundType());
        payload.put("title", request.getTitle());
        payload.put("remark", request.getRemark());
        payload.put("voucherFileIds", request.getVoucherFileIds());
        if (shareholderAll) {
            payload.put("forceShareholderAll", true);
        }
        submit.setPayload(payload);
        WfApproval approval = wfApprovalService.submit(submit);
        String msg = shareholderAll ? "已提交全体股东会签，通过后才会出账" : "已提交财务审批，通过后才会入账";
        if ("EXPENSE".equals(request.getBizType()) && !shareholderAll) {
            msg = "已提交财务审批，通过后才会出账";
        }
        return LedgerRegisterResult.approval(approval, msg);
    }

    /** @return true 若至少通知到一名股东（不含操作者本人） */
    private boolean notifyShareholdersExpense(Long companyId, BigDecimal amount, String title, String remark) {
        List<Long> shareholderIds = dataScopeService.listUserIdsByRoleCodeInCompany("shareholder", companyId);
        if (shareholderIds.isEmpty()) {
            return false;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        List<Long> targets = shareholderIds.stream().filter(id -> !Objects.equals(id, loginId)).toList();
        if (targets.isEmpty()) {
            return false;
        }
        String operator = "用户";
        SysUser u = userService.getById(loginId);
        if (u != null) {
            operator = StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
        }
        String subject = StringUtils.hasText(title) ? title : "公司出账";
        String content = operator + " 登记公司出账 " + amount.stripTrailingZeros().toPlainString()
                + " 元（" + subject + "）"
                + (StringUtils.hasText(remark) ? "，备注：" + remark.trim() : "")
                + "，已直接出账，请知悉。";
        notificationService.notifyUsers(
                targets,
                "公司出账通知 · " + amount.stripTrailingZeros().toPlainString() + " 元",
                content,
                "ledger_expense",
                null,
                "/finance/ledger");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleProject(ProjectSettleRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("结算金额必须大于 0");
        }
        PmProject project = requireProject(request.getProjectId());
        List<PmProjectMember> members = listProjectMembers(project.getId());
        BigDecimal percentSum = members.stream()
                .map(m -> m.getPercent() == null ? BigDecimal.ZERO : m.getPercent())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (percentSum.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("项目分成合计必须为 100%");
        }

        List<ShareAllocation> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < members.size(); i++) {
            PmProjectMember member = members.get(i);
            BigDecimal share;
            if (i == members.size() - 1) {
                share = request.getAmount().subtract(allocated);
            } else {
                share = request.getAmount().multiply(member.getPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            shares.add(new ShareAllocation(member.getUserId(), share, member.getLayer()));
        }
        executeProjectDistribution(project, request.getAmount(), shares, request.getRemark(), "项目预设分钱");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleProjectManual(ProjectManualSettleRequest request) {
        PmProject project = requireProject(request.getProjectId());
        List<ShareAllocation> shares = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ManualShareItem item : request.getItems()) {
            if (item.getUserId() == null) {
                throw new BusinessException("分钱人员不能为空");
            }
            if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("分钱金额必须大于 0");
            }
            shares.add(new ShareAllocation(item.getUserId(), item.getAmount(), item.getLayer()));
            total = total.add(item.getAmount());
        }
        executeProjectDistribution(project, total, shares, request.getRemark(), "财务手动分钱");
    }

    private PmProject requireProject(Long projectId) {
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    private List<PmProjectMember> listProjectMembers(Long projectId) {
        List<PmProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId));
        if (members.isEmpty()) {
            throw new BusinessException("项目未配置参与人和分成");
        }
        return members;
    }

    private void executeProjectDistribution(PmProject project, BigDecimal totalAmount, List<ShareAllocation> shares,
                                            String remark, String modeTitle) {
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("分钱总额必须大于 0");
        }
        BigDecimal shareSum = shares.stream().map(ShareAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shareSum.compareTo(totalAmount) != 0) {
            throw new BusinessException("各参与人金额合计必须等于分钱总额");
        }

        BigDecimal budget = project.getBudget() == null ? BigDecimal.ZERO : project.getBudget();
        BigDecimal settled = project.getSettledAmount() == null ? BigDecimal.ZERO : project.getSettledAmount();
        if (budget.compareTo(BigDecimal.ZERO) > 0
                && settled.add(totalAmount).compareTo(budget) > 0) {
            throw new BusinessException("分钱总额超过项目剩余可分金额（预算 " + budget + "，已结算 " + settled + "）");
        }

        FinPool pool = project.getPoolId() != null ? requirePool(project.getPoolId()) : getDefaultPool();
        if (pool == null) {
            throw new BusinessException("资金池不存在");
        }
        ensurePoolEnabled(pool);
        BigDecimal poolBefore = pool.getBalance();
        debitPool(pool, totalAmount);
        FinPool poolAfter = getById(pool.getId());

        Long batchId = writeLedger("SETTLE", "POOL", pool.getId(), null, totalAmount.negate(),
                poolBefore, poolAfter.getBalance(),
                project.getId(), null, modeTitle + "扣款：" + project.getName(), remark);
        linkBatch(batchId);

        for (ShareAllocation share : shares) {
            if (share.amount() == null || share.amount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            walletService.getOrCreate(share.userId());
            HrWallet walletBeforeState = walletService.getOrCreate(share.userId());
            BigDecimal walletBefore = walletBeforeState.getBalance();
            walletService.changeBalance(share.userId(), share.amount());
            HrWallet walletAfter = walletService.getOrCreate(share.userId());
            String layer = StringUtils.hasText(share.layer()) ? " / " + share.layer() : "";
            writeLedger("SETTLE", "WALLET", pool.getId(), share.userId(), share.amount(), walletBefore, walletAfter.getBalance(),
                    project.getId(), batchId, modeTitle + "：" + project.getName() + layer, remark);
        }

        project.setSettledAmount(settled.add(totalAmount));
        projectMapper.updateById(project);
    }

    private record ShareAllocation(Long userId, BigDecimal amount, String layer) {
    }

    @Override
    public Map<String, Object> summary() {
        Map<String, Object> map = new HashMap<>();
        LambdaQueryWrapper<FinPool> poolWrapper = new LambdaQueryWrapper<FinPool>().orderByAsc(FinPool::getId);
        applyPoolCompanyFilter(poolWrapper);
        List<FinPool> pools = list(poolWrapper);
        BigDecimal poolTotal = pools.stream()
                .map(p -> p.getBalance() == null ? BigDecimal.ZERO : p.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> walletAgg = walletMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<HrWallet>()
                        .select("IFNULL(SUM(balance), 0) AS total", "COUNT(*) AS cnt")
        ).stream().findFirst().orElse(Map.of());
        BigDecimal walletTotal = new BigDecimal(String.valueOf(walletAgg.getOrDefault("total", "0")));
        long walletCount = Long.parseLong(String.valueOf(walletAgg.getOrDefault("cnt", "0")));

        QueryWrapper<FinProjectAccount> projectAggWrapper = new QueryWrapper<FinProjectAccount>()
                .select("IFNULL(SUM(balance), 0) AS total");
        if (!isLoginGlobalAdmin()) {
            Set<Long> companies = visibleCompanies();
            if (companies.isEmpty()) {
                projectAggWrapper.eq("id", -1);
            } else {
                projectAggWrapper.in("company_id", companies);
            }
        }
        Map<String, Object> projectAgg = projectAccountMapper.selectMaps(projectAggWrapper)
                .stream().findFirst().orElse(Map.of());
        BigDecimal projectTotal = new BigDecimal(String.valueOf(projectAgg.getOrDefault("total", "0")));
        BigDecimal assetsTotal = poolTotal.add(projectTotal).add(walletTotal);

        map.put("poolTotal", poolTotal);
        map.put("projectTotal", projectTotal);
        map.put("walletTotal", walletTotal);
        map.put("assetsTotal", assetsTotal);
        map.put("poolCount", pools.size());
        map.put("walletCount", walletCount);
        LambdaQueryWrapper<PmProject> projectCountWrapper = new LambdaQueryWrapper<>();
        applyProjectCompanyFilter(projectCountWrapper);
        map.put("projectCount", projectMapper.selectCount(projectCountWrapper));
        fillPoolCompanyNames(pools);
        map.put("pools", pools);
        List<Map<String, Object>> companyAssets = buildCompanyAssetBalances(pools);
        map.put("companyAssets", companyAssets);
        // 公司余额明细：只展示有资金池的公司
        map.put("companyBalances", companyAssets.stream()
                .filter(r -> asInt(r.get("poolCount")) > 0)
                .sorted((a, b) -> toBigDecimal(b.get("balance")).compareTo(toBigDecimal(a.get("balance"))))
                .collect(Collectors.toList()));
        map.put("projectBalances", buildProjectBalances());
        return map;
    }

    /** 项目余额明细：按项目列出账款余额（含公司归属） */
    private List<Map<String, Object>> buildProjectBalances() {
        LambdaQueryWrapper<FinProjectAccount> wrapper = new LambdaQueryWrapper<FinProjectAccount>()
                .orderByDesc(FinProjectAccount::getBalance)
                .orderByDesc(FinProjectAccount::getId);
        if (!isLoginGlobalAdmin()) {
            Set<Long> companies = visibleCompanies();
            if (companies.isEmpty()) {
                return List.of();
            }
            wrapper.in(FinProjectAccount::getCompanyId, companies);
        }
        List<FinProjectAccount> accounts = projectAccountMapper.selectList(wrapper);
        if (accounts.isEmpty()) {
            return List.of();
        }
        Set<Long> projectIds = accounts.stream()
                .map(FinProjectAccount::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PmProject> projectMap = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds))
                .stream().collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Set<Long> companyIds = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinProjectAccount acc : accounts) {
            PmProject project = projectMap.get(acc.getProjectId());
            if (project == null) {
                continue;
            }
            String scale = project.getScale() == null ? ProjectScales.NORMAL : project.getScale();
            if (!ProjectScales.isFinanceVisible(scale)) {
                continue;
            }
            BigDecimal bal = acc.getBalance() == null ? BigDecimal.ZERO : acc.getBalance();
            // 明细默认展示有余额的项目；全 0 时也保留几条便于确认
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", acc.getProjectId());
            row.put("projectName", project.getName());
            row.put("projectCode", project.getCode());
            row.put("parentId", project.getParentId());
            row.put("scale", scale);
            row.put("companyId", acc.getCompanyId() != null ? acc.getCompanyId() : project.getCompanyId());
            row.put("balance", bal);
            row.put("advanceAmount", acc.getAdvanceAmount() == null ? BigDecimal.ZERO : acc.getAdvanceAmount());
            if (row.get("companyId") != null) {
                companyIds.add((Long) row.get("companyId"));
            }
            rows.add(row);
        }
        Map<Long, String> companyNameMap = companyIds.isEmpty() ? Map.of()
                : deptService.listByIds(companyIds).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (Map<String, Object> row : rows) {
            Long cid = (Long) row.get("companyId");
            if (cid != null) {
                row.put("companyName", companyNameMap.get(cid));
            }
        }
        boolean anyPositive = rows.stream()
                .anyMatch(r -> toBigDecimal(r.get("balance")).compareTo(BigDecimal.ZERO) != 0);
        if (anyPositive) {
            rows = rows.stream()
                    .filter(r -> toBigDecimal(r.get("balance")).compareTo(BigDecimal.ZERO) != 0)
                    .collect(Collectors.toList());
        }
        rows.sort((a, b) -> toBigDecimal(b.get("balance")).compareTo(toBigDecimal(a.get("balance"))));
        return rows;
    }

    /**
     * 按公司汇总：公司余额 + 项目余额；个人钱包不按公司拆分（见 summary.walletTotal）。
     */
    private List<Map<String, Object>> buildCompanyAssetBalances(List<FinPool> pools) {
        Map<Long, Map<String, Object>> byCompany = new LinkedHashMap<>();

        // 1) 公司资金池
        if (pools != null) {
            for (FinPool pool : pools) {
                Long companyId = pool.getCompanyId();
                if (companyId == null) {
                    continue;
                }
                Map<String, Object> row = companyAssetRow(byCompany, companyId, pool.getCompanyName());
                BigDecimal bal = pool.getBalance() == null ? BigDecimal.ZERO : pool.getBalance();
                row.put("poolBalance", ((BigDecimal) row.get("poolBalance")).add(bal));
                row.put("poolCount", ((Integer) row.get("poolCount")) + 1);
            }
        }

        // 2) 项目账款（按公司）
        QueryWrapper<FinProjectAccount> projectWrapper = new QueryWrapper<FinProjectAccount>()
                .select("company_id AS companyId", "IFNULL(SUM(balance), 0) AS total");
        if (!isLoginGlobalAdmin()) {
            Set<Long> companies = visibleCompanies();
            if (companies.isEmpty()) {
                projectWrapper.eq("id", -1);
            } else {
                projectWrapper.in("company_id", companies);
            }
        }
        projectWrapper.groupBy("company_id");
        for (Map<String, Object> agg : projectAccountMapper.selectMaps(projectWrapper)) {
            Long companyId = toLong(agg.get("companyId"));
            if (companyId == null) {
                companyId = toLong(agg.get("company_id"));
            }
            if (companyId == null) {
                continue;
            }
            Map<String, Object> row = companyAssetRow(byCompany, companyId, null);
            row.put("projectBalance", new BigDecimal(String.valueOf(agg.getOrDefault("total", "0"))));
        }

        // 补全可见公司行（个人钱包不按公司拆，仅顶部 summary.walletTotal 一个总数）
        for (Long companyId : visibleCompanies()) {
            companyAssetRow(byCompany, companyId, null);
        }

        // 补公司名 + 合计字段（小计 = 公司余额 + 项目余额）
        Set<Long> needNames = byCompany.values().stream()
                .filter(r -> !StringUtils.hasText((String) r.get("companyName")))
                .map(r -> (Long) r.get("companyId"))
                .collect(Collectors.toSet());
        if (!needNames.isEmpty()) {
            Map<Long, String> nameMap = deptService.listByIds(needNames).stream()
                    .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
            for (Map<String, Object> row : byCompany.values()) {
                if (!StringUtils.hasText((String) row.get("companyName"))) {
                    row.put("companyName", nameMap.get(row.get("companyId")));
                }
            }
        }
        for (Map<String, Object> row : byCompany.values()) {
            BigDecimal poolBal = toBigDecimal(row.get("poolBalance"));
            BigDecimal projectBal = toBigDecimal(row.get("projectBalance"));
            BigDecimal subtotal = poolBal.add(projectBal);
            row.put("balance", poolBal); // 公司余额明细兼容
            row.put("walletBalance", BigDecimal.ZERO); // 个人钱包不按公司拆
            row.put("companyProjectTotal", subtotal);
            row.put("assetsTotal", subtotal);
        }
        return byCompany.values().stream()
                .filter(r -> asInt(r.get("poolCount")) > 0
                        || toBigDecimal(r.get("projectBalance")).compareTo(BigDecimal.ZERO) != 0
                        || toBigDecimal(r.get("assetsTotal")).compareTo(BigDecimal.ZERO) != 0)
                .sorted((a, b) -> toBigDecimal(b.get("assetsTotal")).compareTo(toBigDecimal(a.get("assetsTotal"))))
                .collect(Collectors.toList());
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private Map<String, Object> companyAssetRow(Map<Long, Map<String, Object>> byCompany,
                                                Long companyId, String companyName) {
        Map<String, Object> row = byCompany.computeIfAbsent(companyId, id -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("companyId", id);
            m.put("companyName", companyName);
            m.put("poolBalance", BigDecimal.ZERO);
            m.put("projectBalance", BigDecimal.ZERO);
            m.put("walletBalance", BigDecimal.ZERO);
            m.put("poolCount", 0);
            m.put("balance", BigDecimal.ZERO);
            m.put("assetsTotal", BigDecimal.ZERO);
            return m;
        });
        if (!StringUtils.hasText((String) row.get("companyName")) && StringUtils.hasText(companyName)) {
            row.put("companyName", companyName);
        }
        return row;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long transfer(LedgerCreateRequest request) {
        if (request.getPoolId() == null || request.getUserId() == null) {
            throw new BusinessException("划转必须指定资金池和人员");
        }
        FinPool pool = requirePool(request.getPoolId());
        ensurePoolEnabled(pool);
        walletService.getOrCreate(request.getUserId());
        String title = StringUtils.hasText(request.getTitle()) ? request.getTitle() : "资金池划拨至个人";
        if ("POOL".equals(request.getAccountType())) {
            BigDecimal poolBefore = pool.getBalance();
            debitPool(pool, request.getAmount());
            pool = getById(pool.getId());
            Long batchId = writeLedger("TRANSFER", "POOL", pool.getId(), request.getUserId(), request.getAmount().negate(),
                    poolBefore, pool.getBalance(), request.getProjectId(), null, title, request.getRemark());
            linkBatch(batchId);
            HrWallet wallet = walletService.getOrCreate(request.getUserId());
            BigDecimal walletBefore = wallet.getBalance();
            walletService.changeBalance(request.getUserId(), request.getAmount());
            HrWallet walletAfter = walletService.getOrCreate(request.getUserId());
            writeLedger("TRANSFER", "WALLET", pool.getId(), request.getUserId(), request.getAmount(),
                    walletBefore, walletAfter.getBalance(), request.getProjectId(), batchId, title, request.getRemark());
            return batchId;
        } else if ("WALLET".equals(request.getAccountType())) {
            HrWallet wallet = walletService.getOrCreate(request.getUserId());
            BigDecimal walletBefore = wallet.getBalance();
            walletService.changeBalance(request.getUserId(), request.getAmount().negate());
            HrWallet walletAfter = walletService.getOrCreate(request.getUserId());
            Long batchId = writeLedger("TRANSFER", "WALLET", pool.getId(), request.getUserId(), request.getAmount().negate(),
                    walletBefore, walletAfter.getBalance(), request.getProjectId(), null, title, request.getRemark());
            linkBatch(batchId);
            BigDecimal poolBefore = pool.getBalance();
            creditPool(pool, request.getAmount());
            pool = getById(pool.getId());
            writeLedger("TRANSFER", "POOL", pool.getId(), request.getUserId(), request.getAmount(),
                    poolBefore, pool.getBalance(), request.getProjectId(), batchId, title, request.getRemark());
            return batchId;
        } else {
            throw new BusinessException("划转方向不正确");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverseIncomeRegister(Long poolId, Long channelId, BigDecimal rollbackGross,
                                      BigDecimal originGross, String feeMode, BigDecimal feeValue,
                                      Long approvalId, String title, String remark) {
        if (rollbackGross == null || rollbackGross.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("回退金额必须大于 0");
        }
        BigDecimal baseGross = originGross != null && originGross.compareTo(BigDecimal.ZERO) > 0
                ? originGross : rollbackGross;
        BigDecimal fee = calcFee(baseGross, feeMode, feeValue);
        BigDecimal net = baseGross.subtract(fee);
        BigDecimal ratio = baseGross.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ONE
                : rollbackGross.divide(baseGross, 8, RoundingMode.HALF_UP);
        BigDecimal reverseNet = net.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        if (reverseNet.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("回退净额无效");
        }
        try {
            if (approvalId != null) {
                approvalIdHolder.set(approvalId);
            }
            FinPool pool = poolId != null ? requirePool(poolId) : getDefaultPool();
            if (pool == null) {
                throw new BusinessException("资金池不存在");
            }
            ensurePoolEnabled(pool);
            BigDecimal before = pool.getBalance();
            debitPool(pool, reverseNet);
            pool = getById(pool.getId());
            writeLedger("ROLLBACK", "POOL", pool.getId(), channelId, null,
                    reverseNet.negate(), before, pool.getBalance(), null, null,
                    StringUtils.hasText(title) ? title : "回退入账",
                    StringUtils.hasText(remark) ? remark : ("回退净额 ¥" + reverseNet.toPlainString()
                            + "（原总额 ¥" + baseGross.toPlainString() + "，手续费 ¥" + fee.toPlainString() + "）"),
                    rollbackGross, fee.multiply(ratio).setScale(2, RoundingMode.HALF_UP), feeMode);
            if (channelId != null) {
                payChannelService.debitBalance(channelId, reverseNet);
            }
        } finally {
            approvalIdHolder.remove();
        }
    }

    private Long applyIncomeWithFee(LedgerCreateRequest request) {
        BigDecimal gross = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = calcFee(gross, request.getFeeMode(), request.getFeeValue());
        if (fee.compareTo(gross) >= 0) {
            throw new BusinessException("手续费不能大于或等于入账总额");
        }
        BigDecimal net = gross.subtract(fee);
        if (request.getChannelId() != null) {
            FinPayChannel channel = payChannelService.requireEnabled(request.getChannelId());
            if (request.getPoolId() == null) {
                request.setPoolId(channel.getPoolId());
            } else if (!request.getPoolId().equals(channel.getPoolId())) {
                throw new BusinessException("收款渠道与资金池不匹配");
            }
        }
        FinPool pool = request.getPoolId() != null ? requirePool(request.getPoolId()) : getDefaultPool();
        if (pool == null) {
            throw new BusinessException("资金池不存在");
        }
        request.setPoolId(pool.getId());
        ensurePoolEnabled(pool);

        String title = StringUtils.hasText(request.getTitle()) ? request.getTitle() : "入账";
        // 总额入账留痕
        BigDecimal beforeGross = pool.getBalance();
        creditPool(pool, gross);
        pool = getById(pool.getId());
        Long incomeId = writeLedger("INCOME", "POOL", pool.getId(), request.getChannelId(), request.getUserId(),
                gross, beforeGross, pool.getBalance(), request.getProjectId(), null, title, request.getRemark(),
                gross, fee, request.getFeeMode());

        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal beforeFee = pool.getBalance();
            debitPool(pool, fee);
            pool = getById(pool.getId());
            writeLedger("FEE", "POOL", pool.getId(), request.getChannelId(), request.getUserId(),
                    fee.negate(), beforeFee, pool.getBalance(), request.getProjectId(), incomeId,
                    "支付渠道手续费", "从入账总额中扣除；净入账 ¥" + net.toPlainString(),
                    gross, fee, request.getFeeMode());
        }
        // 渠道记净额（实际到账）
        if (request.getChannelId() != null) {
            payChannelService.creditBalance(request.getChannelId(), net);
        }
        // 关联项目：净额拨入项目指定资金池（公司池同步扣减，与预支同口径）
        if (request.getProjectId() != null) {
            String fundType = ProjectFundTypes.require(request.getFundType());
            projectAccountService.creditProjectFund(
                    request.getProjectId(),
                    request.getPoolId(),
                    net,
                    fundType,
                    request.getApprovalId(),
                    title,
                    request.getRemark());
        }
        return incomeId;
    }

    private void validateLedgerFundType(LedgerCreateRequest request) {
        if (request == null) {
            return;
        }
        if (request.getProjectId() != null) {
            if (!"INCOME".equals(request.getBizType())) {
                return;
            }
            request.setFundType(ProjectFundTypes.require(request.getFundType()));
            projectAccountService.assertMutableProject(request.getProjectId());
        } else if (StringUtils.hasText(request.getFundType())) {
            throw new BusinessException("未关联项目时不能指定资金类型");
        }
    }

    private BigDecimal calcFee(BigDecimal gross, String feeMode, BigDecimal feeValue) {
        if (!StringUtils.hasText(feeMode) || feeValue == null || feeValue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equalsIgnoreCase(feeMode)) {
            return gross.multiply(feeValue).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        if ("FIXED".equalsIgnoreCase(feeMode)) {
            return feeValue.setScale(2, RoundingMode.HALF_UP);
        }
        throw new BusinessException("手续费方式仅支持 FIXED / PERCENT");
    }

    private Long applyChange(LedgerCreateRequest request, BigDecimal signedAmount) {
        return applyChange(request, signedAmount, request.getChannelId(), null, null, null);
    }

    private Long applyChange(LedgerCreateRequest request, BigDecimal signedAmount,
                             Long channelId, BigDecimal grossAmount, BigDecimal feeAmount, String feeMode) {
        if ("WALLET".equals(request.getAccountType())) {
            throw new BusinessException("个人钱包不支持直接进出账，请通过项目分钱或报销流程");
        }
        String title = StringUtils.hasText(request.getTitle()) ? request.getTitle() : ("INCOME".equals(request.getBizType()) ? "入账" : "出账");
        FinPool pool = request.getPoolId() != null ? requirePool(request.getPoolId()) : getDefaultPool();
        if (pool == null) {
            throw new BusinessException("资金池不存在");
        }
        request.setPoolId(pool.getId());
        ensurePoolEnabled(pool);
        BigDecimal before = pool.getBalance();
        if (signedAmount.compareTo(BigDecimal.ZERO) > 0) {
            creditPool(pool, signedAmount);
        } else {
            debitPool(pool, signedAmount.abs());
        }
        pool = getById(pool.getId());
        Long ledgerId = writeLedger(request.getBizType(), "POOL", pool.getId(), channelId, request.getUserId(),
                signedAmount, before, pool.getBalance(), request.getProjectId(), null, title, request.getRemark(),
                grossAmount, feeAmount, feeMode);
        // 渠道余额仅随「入账」净额变动；出账不自动扣渠道，避免误伤
        if (channelId != null && "INCOME".equals(request.getBizType()) && signedAmount.compareTo(BigDecimal.ZERO) > 0) {
            payChannelService.creditBalance(channelId, signedAmount);
        }
        return ledgerId;
    }

    private FinPool requirePool(Long poolId) {
        FinPool pool = getById(poolId);
        if (pool == null) {
            throw new BusinessException("资金池不存在");
        }
        assertPoolVisible(pool);
        return pool;
    }

    private void assertPoolVisible(FinPool pool) {
        if (pool == null || isLoginGlobalAdmin()) {
            return;
        }
        Set<Long> companies = visibleCompanies();
        if (pool.getCompanyId() == null || !companies.contains(pool.getCompanyId())) {
            throw new BusinessException("无权操作该资金池");
        }
    }

    private void ensurePoolEnabled(FinPool pool) {
        if (pool.getStatus() != null && pool.getStatus() == 0) {
            throw new BusinessException("资金池已禁用");
        }
    }

    private void debitPool(FinPool pool, BigDecimal amount) {
        boolean ok = lambdaUpdate()
                .eq(FinPool::getId, pool.getId())
                .ge(FinPool::getBalance, amount)
                .setSql("balance = balance - " + amount.toPlainString())
                .update();
        if (!ok) {
            throw new BusinessException("资金池余额不足");
        }
    }

    private void creditPool(FinPool pool, BigDecimal amount) {
        lambdaUpdate()
                .eq(FinPool::getId, pool.getId())
                .setSql("balance = balance + " + amount.toPlainString())
                .update();
    }

    private void linkBatch(Long batchId) {
        if (batchId == null) {
            return;
        }
        FinLedger update = new FinLedger();
        update.setId(batchId);
        update.setRelatedId(batchId);
        ledgerMapper.updateById(update);
    }

    private Long writeLedger(String bizType, String accountType, Long poolId, Long userId, BigDecimal amount,
                             BigDecimal before, BigDecimal after, Long projectId, Long relatedId, String title, String remark) {
        return writeLedger(bizType, accountType, poolId, null, userId, amount, before, after, projectId, relatedId, title, remark,
                null, null, null);
    }

    private Long writeLedger(String bizType, String accountType, Long poolId, Long channelId, Long userId, BigDecimal amount,
                             BigDecimal before, BigDecimal after, Long projectId, Long relatedId, String title, String remark,
                             BigDecimal grossAmount, BigDecimal feeAmount, String feeMode) {
        FinLedger ledger = new FinLedger();
        ledger.setBizNo(bizNoGenerator.ledger());
        ledger.setBizType(bizType);
        ledger.setAccountType(accountType);
        ledger.setPoolId(poolId);
        ledger.setChannelId(channelId);
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setGrossAmount(grossAmount);
        ledger.setFeeAmount(feeAmount);
        ledger.setFeeMode(feeMode);
        ledger.setBeforeBalance(before);
        ledger.setAfterBalance(after);
        ledger.setProjectId(projectId);
        ledger.setRelatedId(relatedId);
        ledger.setApprovalId(approvalIdHolder.get());
        ledger.setTitle(title);
        ledger.setRemark(remark);
        ledger.setOccurTime(LocalDateTime.now());
        ledger.setCompanyId(resolveLedgerCompanyId(poolId, projectId));
        ledgerMapper.insert(ledger);
        return ledger.getId();
    }

    private Long resolveLedgerCompanyId(Long poolId, Long projectId) {
        if (projectId != null) {
            PmProject project = projectMapper.selectById(projectId);
            if (project != null && project.getCompanyId() != null) {
                return project.getCompanyId();
            }
        }
        if (poolId != null) {
            FinPool pool = getById(poolId);
            if (pool != null && pool.getCompanyId() != null) {
                return pool.getCompanyId();
            }
        }
        return resolveLoginCompanyId();
    }

    private Long resolveLoginCompanyId() {
        try {
            long uid = StpUtil.getLoginIdAsLong();
            SysUser user = userService.getById(uid);
            if (user != null) {
                return deptService.resolveCompanyId(user.getDeptId());
            }
        } catch (Exception ignored) {
            // 无登录上下文时跳过
        }
        return null;
    }

    private boolean isLoginGlobalAdmin() {
        try {
            return dataScopeService.isGlobalAdmin(StpUtil.getLoginIdAsLong());
        } catch (Exception ignored) {
            return false;
        }
    }

    private Set<Long> visibleCompanies() {
        try {
            return dataScopeService.visibleCompanyIds(StpUtil.getLoginIdAsLong());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private void applyCompanyFilter(LambdaQueryWrapper<FinLedger> wrapper) {
        if (isLoginGlobalAdmin()) {
            return;
        }
        Set<Long> companies = visibleCompanies();
        if (companies.isEmpty()) {
            wrapper.eq(FinLedger::getId, -1L);
            return;
        }
        wrapper.in(FinLedger::getCompanyId, companies);
    }

    private void applyPoolCompanyFilter(LambdaQueryWrapper<FinPool> wrapper) {
        if (isLoginGlobalAdmin()) {
            return;
        }
        Set<Long> companies = visibleCompanies();
        if (companies.isEmpty()) {
            wrapper.eq(FinPool::getId, -1L);
            return;
        }
        wrapper.in(FinPool::getCompanyId, companies);
    }

    private void applyProjectCompanyFilter(LambdaQueryWrapper<PmProject> wrapper) {
        if (isLoginGlobalAdmin()) {
            return;
        }
        Set<Long> companies = visibleCompanies();
        if (companies.isEmpty()) {
            wrapper.eq(PmProject::getId, -1L);
            return;
        }
        wrapper.in(PmProject::getCompanyId, companies);
    }

    private void bindVouchers(java.util.List<Long> fileIds, Long ledgerId) {
        if (ledgerId == null) {
            return;
        }
        fileService.bindBiz(fileIds, "ledger", ledgerId);
    }

    private void fillLedgers(List<FinLedger> ledgers) {
        if (ledgers == null || ledgers.isEmpty()) {
            return;
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> poolIds = new HashSet<>();
        Set<Long> projectIds = new HashSet<>();
        Set<Long> channelIds = new HashSet<>();
        Set<Long> companyIds = new HashSet<>();
        Set<Long> ledgerIds = new HashSet<>();
        for (FinLedger ledger : ledgers) {
            if (ledger.getUserId() != null) {
                userIds.add(ledger.getUserId());
            }
            if (ledger.getPoolId() != null) {
                poolIds.add(ledger.getPoolId());
            }
            if (ledger.getProjectId() != null) {
                projectIds.add(ledger.getProjectId());
            }
            if (ledger.getChannelId() != null) {
                channelIds.add(ledger.getChannelId());
            }
            if (ledger.getCompanyId() != null) {
                companyIds.add(ledger.getCompanyId());
            }
            if (ledger.getId() != null) {
                ledgerIds.add(ledger.getId());
            }
        }

        // 勿用 Map.of()：流水上 userId/projectId 常为 null，ImmutableMap.get(null) 会 NPE
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u -> userMap.put(u.getId(), u));
        }
        Map<Long, FinPool> poolMap = new HashMap<>();
        if (!poolIds.isEmpty()) {
            listByIds(poolIds).forEach(p -> poolMap.put(p.getId(), p));
        }
        Map<Long, PmProject> projectMap = new HashMap<>();
        if (!projectIds.isEmpty()) {
            projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds))
                    .forEach(p -> projectMap.put(p.getId(), p));
        }
        Map<Long, FinPayChannel> channelMap = new HashMap<>();
        if (!channelIds.isEmpty()) {
            payChannelService.listByIds(channelIds).forEach(c -> channelMap.put(c.getId(), c));
        }
        for (FinPool pool : poolMap.values()) {
            if (pool.getCompanyId() != null) {
                companyIds.add(pool.getCompanyId());
            }
        }
        for (PmProject project : projectMap.values()) {
            if (project.getCompanyId() != null) {
                companyIds.add(project.getCompanyId());
            }
        }
        Map<Long, String> companyNameMap = new HashMap<>();
        if (!companyIds.isEmpty()) {
            deptService.listByIds(companyIds).forEach(d -> companyNameMap.put(d.getId(), d.getName()));
        }
        Map<Long, List<SysFile>> voucherMap = fileService.mapByBiz("ledger", ledgerIds);

        for (FinLedger ledger : ledgers) {
            if (ledger.getUserId() != null) {
                SysUser user = userMap.get(ledger.getUserId());
                if (user != null) {
                    ledger.setUserName(user.getNickname() != null ? user.getNickname() : user.getUsername());
                }
            }
            if (ledger.getPoolId() != null) {
                FinPool pool = poolMap.get(ledger.getPoolId());
                if (pool != null) {
                    ledger.setPoolName(pool.getName());
                    if (ledger.getCompanyId() == null && pool.getCompanyId() != null) {
                        ledger.setCompanyId(pool.getCompanyId());
                    }
                }
            }
            if (ledger.getProjectId() != null) {
                PmProject project = projectMap.get(ledger.getProjectId());
                if (project != null) {
                    ledger.setProjectName(project.getName());
                    if (ledger.getCompanyId() == null && project.getCompanyId() != null) {
                        ledger.setCompanyId(project.getCompanyId());
                    }
                }
            }
            if (ledger.getCompanyId() != null) {
                ledger.setCompanyName(companyNameMap.get(ledger.getCompanyId()));
            }
            if (ledger.getChannelId() != null) {
                FinPayChannel channel = channelMap.get(ledger.getChannelId());
                if (channel != null) {
                    ledger.setChannelName(channel.getName());
                    ledger.setChannelType(channel.getChannelType());
                }
            }
            ledger.setVouchers(voucherMap.getOrDefault(ledger.getId(), List.of()));
        }
    }

    private void applyProjectIdFilter(LambdaQueryWrapper<FinLedger> wrapper, Long projectId) {
        if (projectId == null) {
            return;
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            wrapper.eq(FinLedger::getProjectId, -1L);
            return;
        }
        if (ProjectScales.isMajorShell(project)) {
            List<Long> childIds = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                            .eq(PmProject::getParentId, projectId)
                            .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1))
                            .select(PmProject::getId))
                    .stream()
                    .map(PmProject::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (childIds.isEmpty()) {
                wrapper.eq(FinLedger::getProjectId, -1L);
            } else {
                wrapper.in(FinLedger::getProjectId, childIds);
            }
            return;
        }
        wrapper.eq(FinLedger::getProjectId, projectId);
    }

    private void fillPoolCompanyNames(List<FinPool> pools) {
        if (pools == null || pools.isEmpty()) {
            return;
        }
        Set<Long> companyIds = pools.stream()
                .map(FinPool::getCompanyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (companyIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = deptService.listByIds(companyIds).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (FinPool pool : pools) {
            if (pool.getCompanyId() != null) {
                pool.setCompanyName(nameMap.get(pool.getCompanyId()));
            }
        }
    }

    private void clearDefault(Long companyId) {
        LambdaQueryWrapper<FinPool> wrapper = new LambdaQueryWrapper<FinPool>().eq(FinPool::getIsDefault, 1);
        if (companyId != null) {
            wrapper.eq(FinPool::getCompanyId, companyId);
        }
        List<FinPool> defaults = list(wrapper);
        for (FinPool pool : defaults) {
            pool.setIsDefault(0);
            updateById(pool);
        }
    }
}
