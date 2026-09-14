package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.FinProjectAccount;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.PmProject;
import com.kk.biz.mapper.FinLedgerMapper;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.FinProjectAccountMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.support.BizNoGenerator;
import com.kk.biz.workflow.ProjectScales;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import cn.dev33.satoken.stp.StpUtil;

import java.math.BigDecimal;
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
public class FinProjectAccountServiceImpl extends ServiceImpl<FinProjectAccountMapper, FinProjectAccount>
        implements FinProjectAccountService {

    private final FinLedgerMapper ledgerMapper;
    private final FinPoolMapper poolMapper;
    private final PmProjectMapper projectMapper;
    private final HrWalletService walletService;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final BizNoGenerator bizNoGenerator;
    private final DataScopeService dataScopeService;

    @Override
    public FinProjectAccount getOrCreate(Long projectId) {
        FinProjectAccount account = getOne(new LambdaQueryWrapper<FinProjectAccount>()
                .eq(FinProjectAccount::getProjectId, projectId)
                .last("LIMIT 1"));
        if (account != null) {
            return account;
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        account = new FinProjectAccount();
        account.setProjectId(projectId);
        account.setBalance(BigDecimal.ZERO);
        account.setAdvanceAmount(BigDecimal.ZERO);
        account.setExpenseAmount(BigDecimal.ZERO);
        account.setSettleAmount(BigDecimal.ZERO);
        account.setReserveAmount(project.getReserveAmount() == null ? BigDecimal.ZERO : project.getReserveAmount());
        account.setReserveHeld(BigDecimal.ZERO);
        account.setStatus(1);
        account.setCompanyId(project.getCompanyId());
        save(account);
        return account;
    }

    @Override
    public FinProjectAccount getByProjectId(Long projectId) {
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        String scale = project.getScale() == null ? ProjectScales.NORMAL : project.getScale();
        if (!ProjectScales.isFinanceVisible(scale)) {
            throw new BusinessException("常规项目不涉及财务账款");
        }
        assertProjectVisible(project);
        FinProjectAccount account = getOrCreate(projectId);
        fillExtra(List.of(account), Map.of(project.getId(), project));
        applyMajorShellAggregation(List.of(account), Map.of(project.getId(), project));
        if (!Boolean.TRUE.equals(account.getMajorShell())) {
            assertBalancedQuiet(account);
        }
        return account;
    }

    @Override
    public List<FinProjectAccount> listAccounts(Long companyId, String scale) {
        LambdaQueryWrapper<FinProjectAccount> wrapper = new LambdaQueryWrapper<FinProjectAccount>()
                .orderByDesc(FinProjectAccount::getId);
        long loginId = StpUtil.getLoginIdAsLong();
        Set<Long> companies = null;
        if (!dataScopeService.isGlobalAdmin(loginId)) {
            companies = dataScopeService.visibleCompanyIds(loginId);
            if (companies.isEmpty()) {
                return List.of();
            }
            wrapper.in(FinProjectAccount::getCompanyId, companies);
        }
        if (companyId != null) {
            if (companies != null && !companies.contains(companyId)) {
                return List.of();
            }
            wrapper.eq(FinProjectAccount::getCompanyId, companyId);
        }
        List<FinProjectAccount> list = list(wrapper);
        if (list.isEmpty()) {
            return list;
        }
        // 关联未删除项目，并只保留重点/重大（常规不进财务域）
        Set<Long> projectIds = list.stream().map(FinProjectAccount::getProjectId).collect(Collectors.toSet());
        Map<Long, PmProject> projectMap = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .in(PmProject::getId, projectIds)).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        String scaleFilter = StringUtils.hasText(scale) ? scale.trim().toUpperCase() : null;
        list = list.stream().filter(acc -> {
            PmProject project = projectMap.get(acc.getProjectId());
            if (project == null) {
                return false; // 已删除或不存在
            }
            String projectScale = project.getScale() == null ? ProjectScales.NORMAL : project.getScale();
            if (!ProjectScales.isFinanceVisible(projectScale)) {
                return false;
            }
            if (scaleFilter != null && !scaleFilter.equals(projectScale)) {
                return false;
            }
            // 外层只展示顶层；小项目走 children 接口
            if (project.getParentId() != null) {
                return false;
            }
            return true;
        }).collect(Collectors.toList());
        fillExtra(list, projectMap);
        applyMajorShellAggregation(list, projectMap);
        return list;
    }

    @Override
    public List<FinProjectAccount> listChildAccounts(Long parentProjectId) {
        if (parentProjectId == null) {
            throw new BusinessException("缺少父项目 ID");
        }
        PmProject parent = projectMapper.selectById(parentProjectId);
        if (parent == null) {
            throw new BusinessException("项目不存在");
        }
        if (!ProjectScales.isMajorShell(parent)) {
            throw new BusinessException("仅重大项目可查看小项目账款");
        }
        assertProjectVisible(parent);
        List<PmProject> children = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getParentId, parentProjectId)
                .orderByDesc(PmProject::getId));
        if (children.isEmpty()) {
            return List.of();
        }
        List<FinProjectAccount> accounts = new ArrayList<>();
        Map<Long, PmProject> projectMap = new HashMap<>();
        for (PmProject child : children) {
            projectMap.put(child.getId(), child);
            FinProjectAccount account = getOrCreate(child.getId());
            accounts.add(account);
        }
        fillExtra(accounts, projectMap);
        return accounts;
    }

    @Override
    public void assertMutableProject(Long projectId) {
        if (projectId == null) {
            throw new BusinessException("缺少项目 ID");
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (ProjectScales.isMajorShell(project)) {
            throw new BusinessException("重大项目外壳不可动账，请先创建小项目并在小项目上操作");
        }
        String scale = project.getScale() == null ? ProjectScales.NORMAL : project.getScale();
        if (!ProjectScales.isFinanceVisible(scale)) {
            throw new BusinessException("常规项目不涉及财务账款");
        }
    }

    @Override
    public Page<FinLedger> pageProjectLedgers(long page, long pageSize, Long projectId, String bizType) {
        if (projectId == null) {
            throw new BusinessException("缺少项目 ID");
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (!dataScopeService.isGlobalAdmin(loginId)) {
            Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
            if (project.getCompanyId() == null || !companies.contains(project.getCompanyId())) {
                throw new BusinessException("无权查看该项目流水");
            }
        }
        Page<FinLedger> result = ledgerMapper.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<FinLedger>()
                .eq(FinLedger::getProjectId, projectId)
                .eq(StringUtils.hasText(bizType), FinLedger::getBizType, bizType)
                .orderByDesc(FinLedger::getOccurTime)
                .orderByDesc(FinLedger::getId));
        fillLedgerNames(result.getRecords());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void advanceFromCompany(Long projectId, Long poolId, BigDecimal amount, Long approvalId, String remark) {
        assertMutableProject(projectId);
        requirePositive(amount);
        FinPool pool = requirePool(poolId, projectId);
        FinProjectAccount account = getOrCreate(projectId);
        BigDecimal poolBefore = pool.getBalance();
        debitPool(pool, amount);
        pool = poolMapper.selectById(pool.getId());

        Long companyLedgerId = writeLedger("ADVANCE", "POOL", pool.getId(), null, null, amount.negate(),
                poolBefore, pool.getBalance(), projectId, null, approvalId,
                "项目预支扣款", remark);

        BigDecimal projectBefore = account.getBalance();
        account.setAdvanceAmount(nz(account.getAdvanceAmount()).add(amount));
        account.setBalance(nz(account.getBalance()).add(amount));
        updateById(account);
        // 入账流水：可用余额 = before + amount（预留占用另记）
        writeLedger("ADVANCE", "PROJECT", pool.getId(), null, null, amount,
                projectBefore, account.getBalance(), projectId, companyLedgerId, approvalId,
                "项目预支入账", remark);

        // 若有约定预留且尚未占用，再锁定预留
        BigDecimal needHold = nz(account.getReserveAmount()).subtract(nz(account.getReserveHeld()));
        if (needHold.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal hold = needHold.min(nz(account.getBalance()));
            if (hold.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal beforeHold = account.getBalance();
                account.setReserveHeld(nz(account.getReserveHeld()).add(hold));
                account.setBalance(beforeHold.subtract(hold));
                updateById(account);
                writeLedger("RESERVE", "PROJECT", pool.getId(), null, null, hold.negate(),
                        beforeHold, account.getBalance(), projectId, companyLedgerId, approvalId,
                        "预留占用", "预支后自动锁定预留");
            }
        }
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverseAdvance(Long projectId, Long poolId, BigDecimal amount, Long approvalId, String remark) {
        assertMutableProject(projectId);
        requirePositive(amount);
        FinProjectAccount account = getOrCreate(projectId);
        if (nz(account.getBalance()).compareTo(amount) < 0) {
            throw new BusinessException("项目可用余额不足，无法回退预支");
        }
        if (nz(account.getAdvanceAmount()).compareTo(amount) < 0) {
            throw new BusinessException("回退金额超过累计预支");
        }
        FinPool pool = requirePool(poolId, projectId);
        BigDecimal projectBefore = account.getBalance();
        account.setBalance(projectBefore.subtract(amount));
        account.setAdvanceAmount(nz(account.getAdvanceAmount()).subtract(amount));
        updateById(account);
        Long projectLedger = writeLedger("ROLLBACK", "PROJECT", pool.getId(), null, null, amount.negate(),
                projectBefore, account.getBalance(), projectId, null, approvalId,
                "预支回退出账", remark);

        BigDecimal poolBefore = pool.getBalance();
        creditPool(pool, amount);
        pool = poolMapper.selectById(pool.getId());
        writeLedger("ROLLBACK", "POOL", pool.getId(), null, null, amount,
                poolBefore, pool.getBalance(), projectId, projectLedger, approvalId,
                "预支回退入公司总账", remark);
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expense(Long projectId, BigDecimal amount, Long approvalId, String title, String remark) {
        assertMutableProject(projectId);
        requirePositive(amount);
        FinProjectAccount account = getOrCreate(projectId);
        if (nz(account.getBalance()).compareTo(amount) < 0) {
            throw new BusinessException("项目可用余额不足");
        }
        BigDecimal before = account.getBalance();
        account.setBalance(before.subtract(amount));
        account.setExpenseAmount(nz(account.getExpenseAmount()).add(amount));
        updateById(account);
        writeLedger("EXPENSE", "PROJECT", null, null, null, amount.negate(),
                before, account.getBalance(), projectId, null, approvalId,
                StringUtils.hasText(title) ? title : "项目支出", remark);
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expenseToWallet(Long projectId, Long userId, BigDecimal amount, Long approvalId,
                                String bizType, String title, String remark) {
        assertMutableProject(projectId);
        requirePositive(amount);
        if (userId == null) {
            throw new BusinessException("缺少收款人，无法转入个人钱包");
        }
        String type = StringUtils.hasText(bizType) ? bizType : "EXPENSE";
        FinProjectAccount account = getOrCreate(projectId);
        if (nz(account.getBalance()).compareTo(amount) < 0) {
            throw new BusinessException("项目可用余额不足");
        }
        BigDecimal projectBefore = account.getBalance();
        account.setBalance(projectBefore.subtract(amount));
        account.setExpenseAmount(nz(account.getExpenseAmount()).add(amount));
        updateById(account);

        Long batchId = writeLedger(type, "PROJECT", null, null, null, amount.negate(),
                projectBefore, account.getBalance(), projectId, null, approvalId,
                StringUtils.hasText(title) ? title : "项目支出", remark);

        HrWallet walletBefore = walletService.getOrCreate(userId);
        BigDecimal wb = walletBefore.getBalance();
        walletService.changeBalance(userId, amount);
        HrWallet walletAfter = walletService.getOrCreate(userId);
        writeLedger(type, "WALLET", null, userId, null, amount,
                wb, walletAfter.getBalance(), projectId, batchId, approvalId,
                StringUtils.hasText(title) ? (title + "入钱包") : "项目支出入钱包", remark);
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleToWallets(Long projectId, Long poolId, Map<Long, BigDecimal> shares, Long approvalId, String remark) {
        assertMutableProject(projectId);
        if (shares == null || shares.isEmpty()) {
            throw new BusinessException("分成明细不能为空");
        }
        BigDecimal total = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        requirePositive(total);
        FinProjectAccount account = getOrCreate(projectId);
        if (nz(account.getBalance()).compareTo(total) < 0) {
            throw new BusinessException("项目可用余额不足，无法分成");
        }
        BigDecimal before = account.getBalance();
        account.setBalance(before.subtract(total));
        account.setSettleAmount(nz(account.getSettleAmount()).add(total));
        updateById(account);

        PmProject project = projectMapper.selectById(projectId);
        String projectLabel = project != null && StringUtils.hasText(project.getName())
                ? project.getName().trim()
                : ("#" + projectId);
        String debitTitle = "项目分成扣款 · " + projectLabel;
        String creditTitle = "项目分成入账 · " + projectLabel;

        Long batchId = writeLedger("SETTLE", "PROJECT", poolId, null, null, total.negate(),
                before, account.getBalance(), projectId, null, approvalId,
                debitTitle, remark);

        for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
            if (e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            HrWallet walletBefore = walletService.getOrCreate(e.getKey());
            BigDecimal wb = walletBefore.getBalance();
            walletService.changeBalance(e.getKey(), e.getValue());
            HrWallet walletAfter = walletService.getOrCreate(e.getKey());
            writeLedger("SETTLE", "WALLET", poolId, e.getKey(), null, e.getValue(),
                    wb, walletAfter.getBalance(), projectId, batchId, approvalId,
                    creditTitle, remark);
        }

        if (project != null) {
            project.setSettledAmount(nz(project.getSettledAmount()).add(total));
            projectMapper.updateById(project);
        }
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void holdReserve(Long projectId, BigDecimal reserveAmount) {
        assertMutableProject(projectId);
        FinProjectAccount account = getOrCreate(projectId);
        BigDecimal target = reserveAmount == null ? BigDecimal.ZERO : reserveAmount;
        if (target.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("预留金额不能为负");
        }
        account.setReserveAmount(target);
        BigDecimal held = nz(account.getReserveHeld());
        if (target.compareTo(held) > 0) {
            BigDecimal need = target.subtract(held);
            if (nz(account.getBalance()).compareTo(need) < 0) {
                throw new BusinessException("可用余额不足以锁定预留");
            }
            account.setBalance(account.getBalance().subtract(need));
            account.setReserveHeld(held.add(need));
        } else if (target.compareTo(held) < 0) {
            BigDecimal release = held.subtract(target);
            account.setReserveHeld(target);
            account.setBalance(nz(account.getBalance()).add(release));
        }
        updateById(account);
        PmProject project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setReserveAmount(target);
            projectMapper.updateById(project);
        }
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnReserveToCompany(Long projectId, Long poolId, Long approvalId, String remark) {
        assertMutableProject(projectId);
        FinProjectAccount account = getOrCreate(projectId);
        BigDecimal held = nz(account.getReserveHeld());
        BigDecimal available = nz(account.getBalance());
        BigDecimal total = available.add(held);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("项目没有可回公司的结余");
        }
        FinPool pool = requirePool(poolId, projectId);
        BigDecimal projectBefore = account.getBalance();
        // 结余 = 当前余额 + 若有历史预留占用一并退回公司
        account.setBalance(BigDecimal.ZERO);
        account.setReserveHeld(BigDecimal.ZERO);
        account.setReserveAmount(BigDecimal.ZERO);
        account.setAdvanceAmount(nz(account.getAdvanceAmount()).subtract(total));
        if (nz(account.getAdvanceAmount()).compareTo(BigDecimal.ZERO) < 0) {
            account.setAdvanceAmount(BigDecimal.ZERO);
        }
        updateById(account);

        Long projectLedger = writeLedger("RESERVE", "PROJECT", pool.getId(), null, null, total.negate(),
                projectBefore, account.getBalance(), projectId, null, approvalId,
                "项目结余回公司", remark);

        BigDecimal poolBefore = pool.getBalance();
        creditPool(pool, total);
        pool = poolMapper.selectById(pool.getId());
        writeLedger("RESERVE", "POOL", pool.getId(), null, null, total,
                poolBefore, pool.getBalance(), projectId, projectLedger, approvalId,
                "项目结余入公司总账", remark);
        assertBalanced(projectId);
    }

    @Override
    public void assertBalanced(Long projectId) {
        FinProjectAccount account = getOrCreate(projectId);
        BigDecimal expected = nz(account.getAdvanceAmount())
                .subtract(nz(account.getExpenseAmount()))
                .subtract(nz(account.getSettleAmount()))
                .subtract(nz(account.getReserveHeld()));
        if (expected.compareTo(nz(account.getBalance())) != 0) {
            throw new BusinessException("项目账款轧平失败：可用=" + account.getBalance()
                    + "，应有=" + expected + "（预支-支出-分成-预留）");
        }
    }

    private void assertBalancedQuiet(FinProjectAccount account) {
        // 仅用于展示，不抛错
    }

    private Long writeLedger(String bizType, String accountType, Long poolId, Long userId, Long unused,
                             BigDecimal amount, BigDecimal before, BigDecimal after,
                             Long projectId, Long relatedId, Long approvalId, String title, String remark) {
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
        if (projectId != null) {
            PmProject project = projectMapper.selectById(projectId);
            if (project != null) {
                ledger.setCompanyId(project.getCompanyId());
            }
        }
        if (ledger.getCompanyId() == null && poolId != null) {
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

    private FinPool requirePool(Long poolId, Long projectId) {
        Long companyId = null;
        if (projectId != null) {
            PmProject project = projectMapper.selectById(projectId);
            if (project != null) {
                companyId = project.getCompanyId();
            }
        }
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
        if (pool.getStatus() != null && pool.getStatus() == 0) {
            throw new BusinessException("公司账户已禁用");
        }
        if (companyId != null && pool.getCompanyId() != null && !companyId.equals(pool.getCompanyId())) {
            throw new BusinessException("资金池与项目不属于同一公司");
        }
        return pool;
    }

    private void assertProjectVisible(PmProject project) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (project.getCompanyId() == null || !companies.contains(project.getCompanyId())) {
            throw new BusinessException("无权查看该项目账户");
        }
    }

    private void debitPool(FinPool pool, BigDecimal amount) {
        boolean ok = new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(poolMapper)
                .eq(FinPool::getId, pool.getId())
                .ge(FinPool::getBalance, amount)
                .setSql("balance = balance - " + amount.toPlainString())
                .update();
        if (!ok) {
            throw new BusinessException("公司总账余额不足");
        }
    }

    private void creditPool(FinPool pool, BigDecimal amount) {
        new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(poolMapper)
                .eq(FinPool::getId, pool.getId())
                .setSql("balance = balance + " + amount.toPlainString())
                .update();
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于 0");
        }
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void fillExtra(List<FinProjectAccount> list, Map<Long, PmProject> cachedProjects) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> projectIds = list.stream().map(FinProjectAccount::getProjectId).collect(Collectors.toSet());
        Map<Long, PmProject> projectMap = cachedProjects != null ? cachedProjects : projectMapper.selectList(
                        new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds)).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Set<Long> ownerIds = new HashSet<>();
        Set<Long> companyIds = new HashSet<>();
        projectMap.values().forEach(p -> {
            if (p.getOwnerId() != null) {
                ownerIds.add(p.getOwnerId());
            }
            if (p.getCompanyId() != null) {
                companyIds.add(p.getCompanyId());
            }
        });
        list.forEach(a -> {
            if (a.getCompanyId() != null) {
                companyIds.add(a.getCompanyId());
            }
        });
        Map<Long, SysUser> userMap = ownerIds.isEmpty() ? Map.of()
                : userService.listByIds(ownerIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, String> companyNameMap = companyIds.isEmpty() ? Map.of()
                : deptService.listByIds(companyIds).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (FinProjectAccount account : list) {
            PmProject project = projectMap.get(account.getProjectId());
            if (project != null) {
                account.setProjectName(project.getName());
                account.setScale(project.getScale());
                account.setParentId(project.getParentId());
                account.setMajorShell(ProjectScales.isMajorShell(project));
                if (account.getCompanyId() == null) {
                    account.setCompanyId(project.getCompanyId());
                }
                SysUser owner = userMap.get(project.getOwnerId());
                if (owner != null) {
                    account.setOwnerName(owner.getNickname() != null ? owner.getNickname() : owner.getUsername());
                }
            } else {
                account.setMajorShell(false);
            }
            if (account.getCompanyId() != null) {
                account.setCompanyName(companyNameMap.get(account.getCompanyId()));
            }
        }
    }

    /**
     * 重大外壳：展示小项目账款汇总；无小项目时保留外壳自身历史数字（只读）。
     */
    private void applyMajorShellAggregation(List<FinProjectAccount> list, Map<Long, PmProject> projectMap) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> shellIds = list.stream()
                .map(FinProjectAccount::getProjectId)
                .filter(id -> {
                    PmProject p = projectMap.get(id);
                    return ProjectScales.isMajorShell(p);
                })
                .collect(Collectors.toSet());
        if (shellIds.isEmpty()) {
            return;
        }
        List<PmProject> children = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                .in(PmProject::getParentId, shellIds)
                .select(PmProject::getId, PmProject::getParentId));
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        Set<Long> childIds = new HashSet<>();
        for (PmProject child : children) {
            if (child.getParentId() == null || child.getId() == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(child.getParentId(), k -> new ArrayList<>()).add(child.getId());
            childIds.add(child.getId());
        }
        Map<Long, FinProjectAccount> childAccountMap = childIds.isEmpty() ? Map.of()
                : list(new LambdaQueryWrapper<FinProjectAccount>().in(FinProjectAccount::getProjectId, childIds))
                .stream().collect(Collectors.toMap(FinProjectAccount::getProjectId, a -> a, (a, b) -> a));
        for (FinProjectAccount account : list) {
            Long projectId = account.getProjectId();
            if (!shellIds.contains(projectId)) {
                account.setMajorShell(false);
                continue;
            }
            account.setMajorShell(true);
            List<Long> kids = childrenByParent.getOrDefault(projectId, List.of());
            account.setChildCount(kids.size());
            if (kids.isEmpty()) {
                continue;
            }
            BigDecimal balance = BigDecimal.ZERO;
            BigDecimal advance = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            BigDecimal settle = BigDecimal.ZERO;
            BigDecimal reserve = BigDecimal.ZERO;
            BigDecimal reserveHeld = BigDecimal.ZERO;
            for (Long childId : kids) {
                FinProjectAccount childAcc = childAccountMap.get(childId);
                if (childAcc == null) {
                    continue;
                }
                balance = balance.add(nz(childAcc.getBalance()));
                advance = advance.add(nz(childAcc.getAdvanceAmount()));
                expense = expense.add(nz(childAcc.getExpenseAmount()));
                settle = settle.add(nz(childAcc.getSettleAmount()));
                reserve = reserve.add(nz(childAcc.getReserveAmount()));
                reserveHeld = reserveHeld.add(nz(childAcc.getReserveHeld()));
            }
            account.setBalance(balance);
            account.setAdvanceAmount(advance);
            account.setExpenseAmount(expense);
            account.setSettleAmount(settle);
            account.setReserveAmount(reserve);
            account.setReserveHeld(reserveHeld);
        }
    }

    private void fillLedgerNames(List<FinLedger> ledgers) {
        if (ledgers == null || ledgers.isEmpty()) {
            return;
        }
        Set<Long> userIds = ledgers.stream().map(FinLedger::getUserId).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u -> userMap.put(u.getId(), u));
        }
        for (FinLedger ledger : ledgers) {
            if (ledger.getUserId() == null) {
                continue;
            }
            SysUser user = userMap.get(ledger.getUserId());
            if (user != null) {
                ledger.setUserName(user.getNickname() != null ? user.getNickname() : user.getUsername());
            }
        }
    }
}
