package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.FinProjectAccount;
import com.kk.biz.entity.FinLedger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FinProjectAccountService extends IService<FinProjectAccount> {

    FinProjectAccount getOrCreate(Long projectId);

    FinProjectAccount getByProjectId(Long projectId);

    /**
     * 项目账款列表：仅未删除且规模为重点/重大的顶层项目；可选公司、规模筛选。
     * 重大外壳金额为小项目汇总。
     */
    List<FinProjectAccount> listAccounts(Long companyId, String scale);

    /** 重大外壳下的小项目账款列表 */
    List<FinProjectAccount> listChildAccounts(Long parentProjectId);

    Page<FinLedger> pageProjectLedgers(long page, long pageSize, Long projectId, String bizType);

    /**
     * 个人可申请余额的项目：可见范围内、重点/重大可动账项目（不含重大外壳），附带结余。
     */
    List<FinProjectAccount> listBalanceApplyCandidates();

    /** 重大外壳禁止动账 */
    void assertMutableProject(Long projectId);

    /** 从公司总账预支到项目 */
    void advanceFromCompany(Long projectId, Long poolId, BigDecimal amount, Long approvalId, String remark);

    /** 预支回退：项目退回公司总账 */
    void reverseAdvance(Long projectId, Long poolId, BigDecimal amount, Long approvalId, String remark);

    /** 项目支出（报销/工资），扣项目可用余额 */
    void expense(Long projectId, BigDecimal amount, Long approvalId, String title, String remark);

    /**
     * 项目支出转入个人钱包：项目结余−、钱包+；公司总账不动。
     * @param bizType 流水类型，如 SALARY / REIMBURSE
     */
    void expenseToWallet(Long projectId, Long userId, BigDecimal amount, Long approvalId,
                         String bizType, String title, String remark);

    /** 项目分成到个人 */
    void settleToWallets(Long projectId, Long poolId, Map<Long, BigDecimal> shares, Long approvalId, String remark);

    /** 锁定/调整预留占用（一般不再由配置触发） */
    void holdReserve(Long projectId, BigDecimal reserveAmount);

    /** 项目结束：结余（剩余余额）回公司总账 */
    void returnReserveToCompany(Long projectId, Long poolId, Long approvalId, String remark);

    /** 校验轧平：balance = advance - expense - settle - reserveHeld */
    void assertBalanced(Long projectId);
}
