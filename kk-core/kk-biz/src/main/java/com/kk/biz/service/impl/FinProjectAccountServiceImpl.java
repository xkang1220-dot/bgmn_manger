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
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final BizNoGenerator bizNoGenerator;

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
        save(account);
        return account;
    }

    @Override
    public FinProjectAccount getByProjectId(Long projectId) {
        FinProjectAccount account = getOrCreate(projectId);
        fillExtra(List.of(account));
        assertBalancedQuiet(account);
        return account;
    }

    @Override
    public List<FinProjectAccount> listAccounts() {
        List<FinProjectAccount> list = list(new LambdaQueryWrapper<FinProjectAccount>()
                .orderByDesc(FinProjectAccount::getId));
        fillExtra(list);
        return list;
    }

    @Override
    public Page<FinLedger> pageProjectLedgers(long page, long pageSize, Long projectId, String bizType) {
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
        requirePositive(amount);
        FinPool pool = requirePool(poolId);
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
        requirePositive(amount);
        FinProjectAccount account = getOrCreate(projectId);
        if (nz(account.getBalance()).compareTo(amount) < 0) {
            throw new BusinessException("项目可用余额不足，无法回退预支");
        }
        if (nz(account.getAdvanceAmount()).compareTo(amount) < 0) {
            throw new BusinessException("回退金额超过累计预支");
        }
        FinPool pool = requirePool(poolId);
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
    public void settleToWallets(Long projectId, Long poolId, Map<Long, BigDecimal> shares, Long approvalId, String remark) {
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

        Long batchId = writeLedger("SETTLE", "PROJECT", poolId, null, null, total.negate(),
                before, account.getBalance(), projectId, null, approvalId,
                "项目分成扣款", remark);

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
                    "项目分成入账", remark);
        }

        PmProject project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setSettledAmount(nz(project.getSettledAmount()).add(total));
            projectMapper.updateById(project);
        }
        assertBalanced(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void holdReserve(Long projectId, BigDecimal reserveAmount) {
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
        FinProjectAccount account = getOrCreate(projectId);
        BigDecimal held = nz(account.getReserveHeld());
        BigDecimal available = nz(account.getBalance());
        BigDecimal total = available.add(held);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("项目没有可回公司的结余");
        }
        FinPool pool = requirePool(poolId);
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
        ledgerMapper.insert(ledger);
        if (relatedId == null) {
            FinLedger link = new FinLedger();
            link.setId(ledger.getId());
            link.setRelatedId(ledger.getId());
            ledgerMapper.updateById(link);
        }
        return ledger.getId();
    }

    private FinPool requirePool(Long poolId) {
        FinPool pool = poolId != null ? poolMapper.selectById(poolId)
                : poolMapper.selectOne(new LambdaQueryWrapper<FinPool>().eq(FinPool::getIsDefault, 1).last("LIMIT 1"));
        if (pool == null) {
            pool = poolMapper.selectOne(new LambdaQueryWrapper<FinPool>().last("LIMIT 1"));
        }
        if (pool == null) {
            throw new BusinessException("公司账户不存在");
        }
        if (pool.getStatus() != null && pool.getStatus() == 0) {
            throw new BusinessException("公司账户已禁用");
        }
        return pool;
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

    private void fillExtra(List<FinProjectAccount> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> projectIds = list.stream().map(FinProjectAccount::getProjectId).collect(Collectors.toSet());
        Map<Long, PmProject> projectMap = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .in(PmProject::getId, projectIds)).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Set<Long> ownerIds = new HashSet<>();
        projectMap.values().forEach(p -> {
            if (p.getOwnerId() != null) {
                ownerIds.add(p.getOwnerId());
            }
        });
        Map<Long, SysUser> userMap = ownerIds.isEmpty() ? Map.of()
                : userService.listByIds(ownerIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (FinProjectAccount account : list) {
            PmProject project = projectMap.get(account.getProjectId());
            if (project != null) {
                account.setProjectName(project.getName());
                SysUser owner = userMap.get(project.getOwnerId());
                if (owner != null) {
                    account.setOwnerName(owner.getNickname() != null ? owner.getNickname() : owner.getUsername());
                }
            }
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
