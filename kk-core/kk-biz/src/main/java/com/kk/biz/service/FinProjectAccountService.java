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

    /** 从公司总账预支到项目（入非分成池，不参与月度分层） */
    void advanceFromCompany(Long projectId, Long poolId, BigDecimal amount, Long approvalId, String remark);

    /**
     * 从公司总账拨入项目指定资金池（入账关联项目 / 预支）。
     * @param fundType SHARE_PENDING / NON_SHARE
     */
    void creditProjectFund(Long projectId, Long poolId, BigDecimal amount, String fundType,
                           Long approvalId, String title, String remark);

    /** 预支回退：项目退回公司总账（默认从非分成扣，兼容旧单） */
    void reverseAdvance(Long projectId, Long poolId, BigDecimal amount, Long approvalId, String remark);

    /**
     * 预支回退：可从待分成/非分成组合扣回公司总账。
     * 两池金额均为 0 时等同于整笔从非分成扣 {@code amount}。
     */
    void reverseAdvance(Long projectId, Long poolId, BigDecimal amount,
                        BigDecimal sharePendingAmount, BigDecimal nonShareAmount,
                        Long approvalId, String remark);

    /** 拨入回退：从指定资金池退回公司总账 */
    void reverseProjectFund(Long projectId, Long poolId, BigDecimal amount, String fundType,
                            Long approvalId, String remark);

    /** 项目支出（报销/工资），默认扣待分成 */
    void expense(Long projectId, BigDecimal amount, Long approvalId, String title, String remark);

    /** 项目支出，指定资金池；余额不足不可跨池拆扣 */
    void expense(Long projectId, BigDecimal amount, String fundType, Long approvalId, String title, String remark);

    /**
     * 项目支出转入个人钱包：项目结余−、钱包+；公司总账不动。默认扣待分成。
     * @param bizType 流水类型，如 SALARY / REIMBURSE
     */
    void expenseToWallet(Long projectId, Long userId, BigDecimal amount, Long approvalId,
                         String bizType, String title, String remark);

    /** 项目支出转入个人钱包，指定资金池 */
    void expenseToWallet(Long projectId, Long userId, BigDecimal amount, String fundType, Long approvalId,
                         String bizType, String title, String remark);

    /** 项目分成到个人（从待分成扣） */
    void settleToWallets(Long projectId, Long poolId, Map<Long, BigDecimal> shares, Long approvalId, String remark);

    /** 从待分成转入预留占用（自然月分层） */
    void holdFromSharePending(Long projectId, BigDecimal amount, Long approvalId, String remark);

    /** 锁定/调整预留占用（一般不再由配置触发） */
    void holdReserve(Long projectId, BigDecimal reserveAmount);

    /** 项目结束：结余整笔回公司（已禁用，调用将抛错；请使用三池拆分重载） */
    void returnReserveToCompany(Long projectId, Long poolId, Long approvalId, String remark);

    /**
     * 结余回公司：可从待分成 / 非分成 / 预留占用分别指定金额（均可为 0，合计须 > 0）。
     */
    void returnReserveToCompany(Long projectId, Long poolId,
                                BigDecimal sharePendingAmount, BigDecimal nonShareAmount, BigDecimal reserveHeldAmount,
                                Long approvalId, String remark);

    /** 仅将当前预留占用回公司总账（自然月预留回笼） */
    void returnReserveHeldToCompany(Long projectId, Long poolId, Long approvalId, String remark);

    /** 校验轧平：balance = advance - expense - settle - reserveHeld；且 balance = 待分成 + 非分成 */
    void assertBalanced(Long projectId);
}
