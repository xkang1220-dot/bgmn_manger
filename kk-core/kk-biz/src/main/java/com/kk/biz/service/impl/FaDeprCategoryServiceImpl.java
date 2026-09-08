package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FaAsset;
import com.kk.biz.entity.FaDeprCategory;
import com.kk.biz.mapper.FaAssetMapper;
import com.kk.biz.mapper.FaDeprCategoryMapper;
import com.kk.biz.service.FaDeprCategoryService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FaDeprCategoryServiceImpl extends ServiceImpl<FaDeprCategoryMapper, FaDeprCategory>
        implements FaDeprCategoryService {

    private final DataScopeService dataScopeService;
    private final SysDeptService deptService;
    private final FaAssetMapper assetMapper;

    @Override
    public Page<FaDeprCategory> pageCategories(long page, long pageSize, Long companyId, String name) {
        long loginId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<FaDeprCategory> w = new LambdaQueryWrapper<FaDeprCategory>()
                .eq(companyId != null, FaDeprCategory::getCompanyId, companyId)
                .like(StringUtils.hasText(name), FaDeprCategory::getName, name)
                .orderByDesc(FaDeprCategory::getId);
        applyCompanyScope(w, loginId);
        Page<FaDeprCategory> result = page(new Page<>(page, pageSize), w);
        fillCompanyNames(result.getRecords());
        return result;
    }

    @Override
    public List<FaDeprCategory> listEnabled(Long companyId) {
        long loginId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<FaDeprCategory> w = new LambdaQueryWrapper<FaDeprCategory>()
                .eq(FaDeprCategory::getStatus, 1)
                .eq(companyId != null, FaDeprCategory::getCompanyId, companyId)
                .orderByAsc(FaDeprCategory::getId);
        applyCompanyScope(w, loginId);
        List<FaDeprCategory> list = list(w);
        fillCompanyNames(list);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createCategory(FaDeprCategory category) {
        validate(category);
        assertCompanyWritable(category.getCompanyId());
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        save(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(FaDeprCategory category) {
        if (category.getId() == null) {
            throw new BusinessException("缺少类别 ID");
        }
        FaDeprCategory db = getById(category.getId());
        if (db == null) {
            throw new BusinessException("折旧类别不存在");
        }
        assertCompanyWritable(db.getCompanyId());
        validate(category);
        category.setCompanyId(db.getCompanyId());
        updateById(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id) {
        FaDeprCategory db = getById(id);
        if (db == null) {
            throw new BusinessException("折旧类别不存在");
        }
        assertCompanyWritable(db.getCompanyId());
        Long used = assetMapper.selectCount(new LambdaQueryWrapper<FaAsset>()
                .eq(FaAsset::getCategoryId, id));
        if (used != null && used > 0) {
            throw new BusinessException("该类别下仍有资产，无法删除");
        }
        removeById(id);
    }

    private void validate(FaDeprCategory category) {
        if (category.getCompanyId() == null) {
            throw new BusinessException("请选择所属公司");
        }
        if (!StringUtils.hasText(category.getName())) {
            throw new BusinessException("请填写类别名称");
        }
        if (category.getMonths() == null || category.getMonths() < 1) {
            throw new BusinessException("折旧月数至少为 1");
        }
        if (category.getResidualRate() == null
                || category.getResidualRate().compareTo(BigDecimal.ZERO) < 0
                || category.getResidualRate().compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("残值率须在 0–1 之间");
        }
    }

    private void applyCompanyScope(LambdaQueryWrapper<FaDeprCategory> w, long loginId) {
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (companies.isEmpty()) {
            w.eq(FaDeprCategory::getId, -1L);
            return;
        }
        w.in(FaDeprCategory::getCompanyId, companies);
    }

    private void assertCompanyWritable(Long companyId) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        if (companyId == null || !dataScopeService.visibleCompanyIds(loginId).contains(companyId)) {
            throw new BusinessException("无权操作该公司折旧类别");
        }
    }

    private void fillCompanyNames(List<FaDeprCategory> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> ids = list.stream().map(FaDeprCategory::getCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = deptService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (FaDeprCategory c : list) {
            c.setCompanyName(nameMap.get(c.getCompanyId()));
        }
    }
}
