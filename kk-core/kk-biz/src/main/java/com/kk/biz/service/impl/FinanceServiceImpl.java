package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.LedgerQuery;
import com.kk.biz.dto.ManualShareItem;
import com.kk.biz.dto.ProjectManualSettleRequest;
import com.kk.biz.dto.ProjectSettleRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinPayChannel;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.FinProjectAccount;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.entity.SysFile;
import com.kk.biz.mapper.FinLedgerMapper;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.FinProjectAccountMapper;
import com.kk.biz.mapper.HrWalletMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.service.FinPayChannelService;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.support.BizNoGenerator;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final SysFileService fileService;
    private final BizNoGenerator bizNoGenerator;
    private final FinPayChannelService payChannelService;
    /** 审批动账时带入流水 approvalId（勿改为构造注入） */
    private final ThreadLocal<Long> approvalIdHolder = ThreadLocal.withInitial(() -> null);

    @Override
    public FinPool getDefaultPool() {
        FinPool pool = getOne(new LambdaQueryWrapper<FinPool>().eq(FinPool::getIsDefault, 1).last("LIMIT 1"));
        if (pool == null) {
            pool = getOne(new LambdaQueryWrapper<FinPool>().last("LIMIT 1"));
        }
        return pool;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createPool(FinPool pool) {
        BigDecimal initial = pool.getBalance() == null ? BigDecimal.ZERO : pool.getBalance();
        if (initial.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("初始余额不能为负数");
        }
        // 余额一律经流水变更：先建池为 0，有初始金额再入账留痕
        pool.setBalance(BigDecimal.ZERO);
        if (pool.getStatus() == null) {
            pool.setStatus(1);
        }
        if (Integer.valueOf(1).equals(pool.getIsDefault())) {
            clearDefault();
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
        if (Integer.valueOf(1).equals(pool.getIsDefault())) {
            clearDefault();
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
                .eq(query.getPoolId() != null, FinLedger::getPoolId, query.getPoolId())
                .eq(query.getProjectId() != null, FinLedger::getProjectId, query.getProjectId())
                .eq(query.getChannelId() != null, FinLedger::getChannelId, query.getChannelId())
                .ge(query.getStartTime() != null, FinLedger::getOccurTime, query.getStartTime())
                .le(query.getEndTime() != null, FinLedger::getOccurTime, query.getEndTime())
                .orderByDesc(FinLedger::getOccurTime)
                .orderByDesc(FinLedger::getId);
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
        Page<FinLedger> result = ledgerMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        fillLedgers(result.getRecords());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createLedger(LedgerCreateRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于 0");
        }
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
        List<FinPool> pools = list(new LambdaQueryWrapper<FinPool>().orderByAsc(FinPool::getId));
        BigDecimal poolTotal = pools.stream()
                .map(p -> p.getBalance() == null ? BigDecimal.ZERO : p.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> walletAgg = walletMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<HrWallet>()
                        .select("IFNULL(SUM(balance), 0) AS total", "COUNT(*) AS cnt")
        ).stream().findFirst().orElse(Map.of());
        BigDecimal walletTotal = new BigDecimal(String.valueOf(walletAgg.getOrDefault("total", "0")));
        long walletCount = Long.parseLong(String.valueOf(walletAgg.getOrDefault("cnt", "0")));

        Map<String, Object> projectAgg = projectAccountMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FinProjectAccount>()
                        .select("IFNULL(SUM(balance), 0) AS total")
        ).stream().findFirst().orElse(Map.of());
        BigDecimal projectTotal = new BigDecimal(String.valueOf(projectAgg.getOrDefault("total", "0")));
        BigDecimal assetsTotal = poolTotal.add(projectTotal).add(walletTotal);

        map.put("poolTotal", poolTotal);
        map.put("projectTotal", projectTotal);
        map.put("walletTotal", walletTotal);
        map.put("assetsTotal", assetsTotal);
        map.put("poolCount", pools.size());
        map.put("walletCount", walletCount);
        map.put("projectCount", projectMapper.selectCount(null));
        map.put("pools", pools);
        return map;
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
        return incomeId;
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
        return pool;
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
        ledgerMapper.insert(ledger);
        return ledger.getId();
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
                }
            }
            if (ledger.getProjectId() != null) {
                PmProject project = projectMap.get(ledger.getProjectId());
                if (project != null) {
                    ledger.setProjectName(project.getName());
                }
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

    private void clearDefault() {
        List<FinPool> defaults = list(new LambdaQueryWrapper<FinPool>().eq(FinPool::getIsDefault, 1));
        for (FinPool pool : defaults) {
            pool.setIsDefault(0);
            updateById(pool);
        }
    }
}
