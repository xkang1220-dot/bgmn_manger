package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.FaAsset;
import com.kk.biz.entity.WfApproval;

public interface FaAssetService extends IService<FaAsset> {

    Page<FaAsset> pageAssets(long page, long pageSize, Long companyId, String status,
                             Long holderUserId, String itemType, String keyword);

    FaAsset detail(Long id);

    void createAsset(FaAsset asset);

    void updateAsset(FaAsset asset);

    FaAsset requireVisible(Long id);

    /** 提交前校验领用 */
    void assertCanBorrow(Long assetId, long applicantId);

    /** 提交前校验归还 */
    void assertCanReturn(Long assetId, long applicantId);

    /** 提交前校验转交（当前领用人 → 指定新领用人） */
    void assertCanTransfer(Long assetId, long applicantId, Long toUserId);

    void effectBorrow(WfApproval approval);

    void effectReturn(WfApproval approval);

    void effectTransfer(WfApproval approval);

    /** 月度折旧，返回处理资产数 */
    int runMonthlyDepreciation();
}
