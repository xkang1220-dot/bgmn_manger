package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FaAsset;
import com.kk.biz.entity.FaAssetEvent;
import com.kk.biz.entity.FaDeprCategory;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.mapper.FaAssetEventMapper;
import com.kk.biz.mapper.FaAssetMapper;
import com.kk.biz.mapper.FaDeprCategoryMapper;
import com.kk.biz.mapper.WfApprovalMapper;
import com.kk.biz.service.FaAssetService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaAssetServiceImpl extends ServiceImpl<FaAssetMapper, FaAsset> implements FaAssetService {

    public static final String STATUS_IN_STOCK = "IN_STOCK";
    public static final String STATUS_IN_USE = "IN_USE";

    private static final ZoneId ZONE_CN = ZoneId.of("Asia/Shanghai");

    private final FaDeprCategoryMapper categoryMapper;
    private final FaAssetEventMapper eventMapper;
    private final WfApprovalMapper approvalMapper;
    private final HrWalletService walletService;
    private final DataScopeService dataScopeService;
    private final SysDeptService deptService;
    private final SysUserService userService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public Page<FaAsset> pageAssets(long page, long pageSize, Long companyId, String status,
                                    Long holderUserId, String keyword) {
        long loginId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<FaAsset> w = new LambdaQueryWrapper<FaAsset>()
                .eq(companyId != null, FaAsset::getCompanyId, companyId)
                .eq(StringUtils.hasText(status), FaAsset::getStatus, status)
                .eq(holderUserId != null, FaAsset::getHolderUserId, holderUserId)
                .orderByDesc(FaAsset::getId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(FaAsset::getAssetCode, kw).or().like(FaAsset::getName, kw));
        }
        applyListScope(w, loginId);
        Page<FaAsset> result = page(new Page<>(page, pageSize), w);
        fillExtras(result.getRecords());
        return result;
    }

    @Override
    public FaAsset detail(Long id) {
        FaAsset asset = requireVisible(id);
        fillExtras(List.of(asset));
        List<FaAssetEvent> events = eventMapper.selectList(new LambdaQueryWrapper<FaAssetEvent>()
                .eq(FaAssetEvent::getAssetId, id)
                .orderByDesc(FaAssetEvent::getId));
        asset.setEvents(events);
        return asset;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createAsset(FaAsset asset) {
        validateNew(asset);
        assertCompanyWritable(asset.getCompanyId());
        FaDeprCategory category = requireCategory(asset.getCategoryId(), asset.getCompanyId());
        long dup = count(new LambdaQueryWrapper<FaAsset>()
                .eq(FaAsset::getCompanyId, asset.getCompanyId())
                .eq(FaAsset::getAssetCode, asset.getAssetCode().trim()));
        if (dup > 0) {
            throw new BusinessException("资产编码在本公司已存在");
        }
        BigDecimal original = asset.getOriginalValue().setScale(2, RoundingMode.HALF_UP);
        BigDecimal residual = original.multiply(nzRate(category.getResidualRate()))
                .setScale(2, RoundingMode.HALF_UP);
        asset.setAssetCode(asset.getAssetCode().trim());
        asset.setName(asset.getName().trim());
        asset.setOriginalValue(original);
        asset.setResidualValue(residual);
        asset.setDeprMonths(category.getMonths());
        asset.setNetValue(original);
        asset.setAccumDepr(BigDecimal.ZERO);
        if (asset.getDeprStartDate() == null) {
            asset.setDeprStartDate(asset.getPurchaseDate() != null ? asset.getPurchaseDate() : LocalDate.now(ZONE_CN));
        }
        asset.setStatus(STATUS_IN_STOCK);
        asset.setHolderUserId(null);
        asset.setFrozenAmount(BigDecimal.ZERO);
        asset.setLockFreeze(0);
        save(asset);
        writeEvent(asset, "CREATE", original, StpUtil.getLoginIdAsLong(), null, "资产入库");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAsset(FaAsset asset) {
        if (asset.getId() == null) {
            throw new BusinessException("缺少资产 ID");
        }
        FaAsset db = requireVisible(asset.getId());
        assertCompanyWritable(db.getCompanyId());
        if (!STATUS_IN_STOCK.equals(db.getStatus())) {
            throw new BusinessException("仅在库资产可编辑");
        }
        if (!StringUtils.hasText(asset.getName())) {
            throw new BusinessException("请填写资产名称");
        }
        db.setName(asset.getName().trim());
        db.setRemark(asset.getRemark());
        db.setPurchaseDate(asset.getPurchaseDate());
        db.setDeprStartDate(asset.getDeprStartDate());

        boolean neverDepr = db.getAccumDepr() == null || db.getAccumDepr().compareTo(BigDecimal.ZERO) == 0;
        if (neverDepr) {
            if (asset.getCategoryId() != null && !Objects.equals(asset.getCategoryId(), db.getCategoryId())) {
                FaDeprCategory category = requireCategory(asset.getCategoryId(), db.getCompanyId());
                db.setCategoryId(category.getId());
                db.setDeprMonths(category.getMonths());
                if (asset.getOriginalValue() == null) {
                    BigDecimal residual = db.getOriginalValue().multiply(nzRate(category.getResidualRate()))
                            .setScale(2, RoundingMode.HALF_UP);
                    db.setResidualValue(residual);
                }
            }
            if (asset.getOriginalValue() != null) {
                if (asset.getOriginalValue().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("原值须大于 0");
                }
                FaDeprCategory category = requireCategory(db.getCategoryId(), db.getCompanyId());
                BigDecimal original = asset.getOriginalValue().setScale(2, RoundingMode.HALF_UP);
                BigDecimal residual = original.multiply(nzRate(category.getResidualRate()))
                        .setScale(2, RoundingMode.HALF_UP);
                db.setOriginalValue(original);
                db.setResidualValue(residual);
                db.setNetValue(original);
                db.setAccumDepr(BigDecimal.ZERO);
            }
            if (StringUtils.hasText(asset.getAssetCode())
                    && !asset.getAssetCode().trim().equals(db.getAssetCode())) {
                String code = asset.getAssetCode().trim();
                long dup = count(new LambdaQueryWrapper<FaAsset>()
                        .eq(FaAsset::getCompanyId, db.getCompanyId())
                        .eq(FaAsset::getAssetCode, code)
                        .ne(FaAsset::getId, db.getId()));
                if (dup > 0) {
                    throw new BusinessException("资产编码在本公司已存在");
                }
                db.setAssetCode(code);
            }
        }
        updateById(db);
    }

    @Override
    public FaAsset requireVisible(Long id) {
        if (id == null) {
            throw new BusinessException("缺少资产 ID");
        }
        FaAsset asset = getById(id);
        if (asset == null) {
            throw new BusinessException("资产不存在");
        }
        assertReadable(asset);
        return asset;
    }

    @Override
    public void assertCanBorrow(Long assetId, long applicantId) {
        FaAsset asset = requireVisible(assetId);
        if (!STATUS_IN_STOCK.equals(asset.getStatus())) {
            throw new BusinessException("仅在库资产可申请领用");
        }
        assertNoPending(assetId);
        assertCompanyVisibleToUser(applicantId, asset.getCompanyId());
        HrWallet wallet = walletService.getOrCreate(applicantId);
        BigDecimal available = nz(wallet.getBalance()).subtract(nz(wallet.getFrozen()));
        if (available.compareTo(nz(asset.getOriginalValue())) < 0) {
            throw new BusinessException("钱包可用余额不足，无法按原值冻结");
        }
    }

    @Override
    public void assertCanReturn(Long assetId, long applicantId) {
        FaAsset asset = requireVisible(assetId);
        if (!STATUS_IN_USE.equals(asset.getStatus())) {
            throw new BusinessException("仅领用中的资产可申请归还");
        }
        if (!Objects.equals(asset.getHolderUserId(), applicantId)) {
            throw new BusinessException("仅当前持有人可申请归还");
        }
        assertNoPending(assetId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void effectBorrow(WfApproval approval) {
        JSONObject payload = JSONUtil.parseObj(approval.getPayload());
        Long assetId = payload.getLong("assetId");
        if (assetId == null) {
            throw new BusinessException("领用审批缺少资产");
        }
        FaAsset asset = getById(assetId);
        if (asset == null) {
            throw new BusinessException("资产不存在");
        }
        if (approval.getCompanyId() != null && !Objects.equals(approval.getCompanyId(), asset.getCompanyId())) {
            throw new BusinessException("审批公司与资产公司不一致");
        }
        if (!STATUS_IN_STOCK.equals(asset.getStatus())) {
            throw new BusinessException("资产已不在库，无法领用");
        }
        Long applicantId = approval.getApplicantId();
        BigDecimal original = nz(asset.getOriginalValue());
        walletService.freeze(applicantId, original);
        boolean ok = lambdaUpdate()
                .eq(FaAsset::getId, assetId)
                .eq(FaAsset::getStatus, STATUS_IN_STOCK)
                .set(FaAsset::getStatus, STATUS_IN_USE)
                .set(FaAsset::getHolderUserId, applicantId)
                .set(FaAsset::getFrozenAmount, original)
                .set(FaAsset::getLockFreeze, 1)
                .update();
        if (!ok) {
            throw new BusinessException("资产状态已变更，领用失败");
        }
        asset.setStatus(STATUS_IN_USE);
        asset.setHolderUserId(applicantId);
        asset.setFrozenAmount(original);
        asset.setLockFreeze(1);
        writeEvent(asset, "BORROW", original, applicantId, approval.getId(), "领用生效，冻结原值");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void effectReturn(WfApproval approval) {
        JSONObject payload = JSONUtil.parseObj(approval.getPayload());
        Long assetId = payload.getLong("assetId");
        if (assetId == null) {
            throw new BusinessException("归还审批缺少资产");
        }
        FaAsset asset = getById(assetId);
        if (asset == null) {
            throw new BusinessException("资产不存在");
        }
        if (!STATUS_IN_USE.equals(asset.getStatus())) {
            throw new BusinessException("资产不是领用中，无法归还");
        }
        Long holderId = asset.getHolderUserId();
        if (holderId == null) {
            throw new BusinessException("资产持有人缺失，无法归还");
        }
        if (!Objects.equals(holderId, approval.getApplicantId())) {
            throw new BusinessException("归还申请人与持有人不一致");
        }
        if (Objects.equals(asset.getLockFreeze(), 1) && nz(asset.getFrozenAmount()).compareTo(BigDecimal.ZERO) > 0) {
            walletService.unfreeze(holderId, asset.getFrozenAmount());
            writeEvent(asset, "UNFREEZE", asset.getFrozenAmount(), approval.getApplicantId(),
                    approval.getId(), "归还解冻");
        }
        boolean ok = lambdaUpdate()
                .eq(FaAsset::getId, assetId)
                .eq(FaAsset::getStatus, STATUS_IN_USE)
                .eq(FaAsset::getHolderUserId, holderId)
                .set(FaAsset::getStatus, STATUS_IN_STOCK)
                .set(FaAsset::getFrozenAmount, BigDecimal.ZERO)
                .set(FaAsset::getLockFreeze, 0)
                .setSql("holder_user_id = NULL")
                .update();
        if (!ok) {
            throw new BusinessException("资产状态已变更，归还失败");
        }
        asset.setStatus(STATUS_IN_STOCK);
        asset.setHolderUserId(null);
        asset.setFrozenAmount(BigDecimal.ZERO);
        asset.setLockFreeze(0);
        writeEvent(asset, "RETURN", asset.getOriginalValue(), approval.getApplicantId(),
                approval.getId(), "归还生效");
    }

    @Override
    public int runMonthlyDepreciation() {
        String ym = YearMonth.now(ZONE_CN).toString();
        LocalDate today = LocalDate.now(ZONE_CN);
        List<FaAsset> assets = list(new LambdaQueryWrapper<FaAsset>()
                .apply("net_value > residual_value")
                .and(w -> w.isNull(FaAsset::getDeprStartDate).or().le(FaAsset::getDeprStartDate, today))
                .and(w -> w.isNull(FaAsset::getLastDeprYm).or().ne(FaAsset::getLastDeprYm, ym)));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int count = 0;
        for (FaAsset item : assets) {
            try {
                Boolean done = tx.execute(status -> depreciateOne(item.getId(), ym));
                if (Boolean.TRUE.equals(done)) {
                    count++;
                }
            } catch (Exception e) {
                log.error("资产月折失败 assetId={}", item.getId(), e);
            }
        }
        return count;
    }

    /** @return true 若本月实际计提 */
    private boolean depreciateOne(Long assetId, String ym) {
        FaAsset asset = getById(assetId);
        if (asset == null) {
            return false;
        }
        if (Objects.equals(ym, asset.getLastDeprYm())) {
            return false;
        }
        if (asset.getDeprMonths() == null || asset.getDeprMonths() < 1) {
            return false;
        }
        BigDecimal original = nz(asset.getOriginalValue());
        BigDecimal residual = nz(asset.getResidualValue());
        BigDecimal net = nz(asset.getNetValue());
        if (net.compareTo(residual) <= 0) {
            return false;
        }
        BigDecimal monthly = original.subtract(residual)
                .divide(BigDecimal.valueOf(asset.getDeprMonths()), 2, RoundingMode.HALF_UP);
        BigDecimal depr = monthly;
        if (net.subtract(monthly).compareTo(residual) < 0) {
            depr = net.subtract(residual);
        }
        if (depr.compareTo(BigDecimal.ZERO) <= 0) {
            asset.setNetValue(residual);
            asset.setAccumDepr(original.subtract(residual));
            asset.setLastDeprYm(ym);
            updateById(asset);
            maybeUnfreezeAfterDepr(asset);
            return true;
        }
        asset.setAccumDepr(nz(asset.getAccumDepr()).add(depr));
        asset.setNetValue(net.subtract(depr));
        asset.setLastDeprYm(ym);
        updateById(asset);
        writeEvent(asset, "DEPR", depr, null, null, "月度折旧 " + ym);
        maybeUnfreezeAfterDepr(asset);
        return true;
    }

    private void maybeUnfreezeAfterDepr(FaAsset asset) {
        if (nz(asset.getNetValue()).compareTo(nz(asset.getResidualValue())) > 0) {
            return;
        }
        if (!Objects.equals(asset.getLockFreeze(), 1)) {
            return;
        }
        Long holderId = asset.getHolderUserId();
        BigDecimal frozen = nz(asset.getFrozenAmount());
        if (holderId != null && frozen.compareTo(BigDecimal.ZERO) > 0) {
            walletService.unfreeze(holderId, frozen);
            writeEvent(asset, "UNFREEZE", frozen, null, null, "折旧至残值，自动解冻");
        }
        lambdaUpdate()
                .eq(FaAsset::getId, asset.getId())
                .eq(FaAsset::getLockFreeze, 1)
                .set(FaAsset::getFrozenAmount, BigDecimal.ZERO)
                .set(FaAsset::getLockFreeze, 0)
                .update();
        asset.setFrozenAmount(BigDecimal.ZERO);
        asset.setLockFreeze(0);
    }

    private void assertNoPending(Long assetId) {
        List<WfApproval> pending = approvalMapper.selectList(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getStatus, "PENDING")
                .in(WfApproval::getType, ApprovalTypes.ASSET_BORROW, ApprovalTypes.ASSET_RETURN));
        for (WfApproval a : pending) {
            if (!StringUtils.hasText(a.getPayload())) {
                continue;
            }
            Long id = JSONUtil.parseObj(a.getPayload()).getLong("assetId");
            if (Objects.equals(id, assetId)) {
                throw new BusinessException("该资产已有进行中的领用/归还审批");
            }
        }
    }

    private void validateNew(FaAsset asset) {
        if (asset.getCompanyId() == null) {
            throw new BusinessException("请选择所属公司");
        }
        if (asset.getCategoryId() == null) {
            throw new BusinessException("请选择折旧类别");
        }
        if (!StringUtils.hasText(asset.getAssetCode())) {
            throw new BusinessException("请填写资产编码");
        }
        if (!StringUtils.hasText(asset.getName())) {
            throw new BusinessException("请填写资产名称");
        }
        if (asset.getOriginalValue() == null || asset.getOriginalValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("原值须大于 0");
        }
    }

    private FaDeprCategory requireCategory(Long categoryId, Long companyId) {
        FaDeprCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("折旧类别不存在");
        }
        if (category.getStatus() != null && category.getStatus() == 0) {
            throw new BusinessException("折旧类别已停用");
        }
        if (!Objects.equals(category.getCompanyId(), companyId)) {
            throw new BusinessException("折旧类别与资产公司不一致");
        }
        return category;
    }

    private void applyListScope(LambdaQueryWrapper<FaAsset> w, long loginId) {
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (companies.isEmpty()) {
            w.eq(FaAsset::getId, -1L);
            return;
        }
        // 本公司全部 +（兜底）本人持有
        w.and(q -> q.in(FaAsset::getCompanyId, companies)
                .or().eq(FaAsset::getHolderUserId, loginId));
    }

    private void assertReadable(FaAsset asset) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        if (Objects.equals(asset.getHolderUserId(), loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (asset.getCompanyId() == null || !companies.contains(asset.getCompanyId())) {
            throw new BusinessException("无权查看该资产");
        }
    }

    private void assertCompanyWritable(Long companyId) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        if (companyId == null || !dataScopeService.visibleCompanyIds(loginId).contains(companyId)) {
            throw new BusinessException("无权操作该公司资产");
        }
    }

    private void assertCompanyVisibleToUser(long userId, Long companyId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        if (companyId == null || !dataScopeService.visibleCompanyIds(userId).contains(companyId)) {
            throw new BusinessException("无权领用其他公司资产");
        }
    }

    private void writeEvent(FaAsset asset, String type, BigDecimal amount, Long operatorId,
                            Long approvalId, String remark) {
        FaAssetEvent event = new FaAssetEvent();
        event.setAssetId(asset.getId());
        event.setCompanyId(asset.getCompanyId());
        event.setEventType(type);
        event.setAmount(amount);
        event.setOperatorId(operatorId);
        event.setApprovalId(approvalId);
        event.setRemark(remark);
        event.setEventTime(LocalDateTime.now(ZONE_CN));
        eventMapper.insert(event);
    }

    private void fillExtras(List<FaAsset> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> companyIds = list.stream().map(FaAsset::getCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> categoryIds = list.stream().map(FaAsset::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> holderIds = list.stream().map(FaAsset::getHolderUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> companyNames = companyIds.isEmpty() ? new HashMap<>()
                : deptService.listByIds(companyIds).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a, HashMap::new));
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? new HashMap<>()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(FaDeprCategory::getId, FaDeprCategory::getName, (a, b) -> a, HashMap::new));
        Map<Long, SysUser> users = holderIds.isEmpty() ? new HashMap<>()
                : userService.listByIds(holderIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a, HashMap::new));
        for (FaAsset a : list) {
            if (a.getCompanyId() != null) {
                a.setCompanyName(companyNames.get(a.getCompanyId()));
            }
            if (a.getCategoryId() != null) {
                a.setCategoryName(categoryNames.get(a.getCategoryId()));
            }
            // 在库资产 holderUserId 为 null；不可对 ImmutableMap 调 get(null)
            if (a.getHolderUserId() == null) {
                continue;
            }
            SysUser u = users.get(a.getHolderUserId());
            if (u != null) {
                a.setHolderName(StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername());
            }
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nzRate(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
