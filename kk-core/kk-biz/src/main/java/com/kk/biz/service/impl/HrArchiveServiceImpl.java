package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.mapper.HrArchiveMapper;
import com.kk.biz.service.HrArchiveService;
import com.kk.biz.service.HrWalletService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class HrArchiveServiceImpl extends ServiceImpl<HrArchiveMapper, HrArchive> implements HrArchiveService {

    private final SysUserService userService;
    private final SysDeptService deptService;
    private final HrWalletService walletService;

    @Override
    public Page<HrArchive> pageArchives(long page, long pageSize, String realName, String employeeNo) {
        Page<HrArchive> result = page(new Page<>(page, pageSize), new LambdaQueryWrapper<HrArchive>()
                .like(StringUtils.hasText(realName), HrArchive::getRealName, realName)
                .like(StringUtils.hasText(employeeNo), HrArchive::getEmployeeNo, employeeNo)
                .orderByDesc(HrArchive::getId));
        result.getRecords().forEach(this::fillUser);
        return result;
    }

    @Override
    public HrArchive getDetail(Long id) {
        HrArchive archive = getById(id);
        if (archive == null) {
            throw new BusinessException("档案不存在");
        }
        fillUser(archive);
        return archive;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createArchive(HrArchive archive) {
        if (archive.getUserId() == null) {
            throw new BusinessException("必须绑定系统账号");
        }
        if (getByUserId(archive.getUserId()) != null) {
            throw new BusinessException("该账号已有档案");
        }
        save(archive);
        walletService.getOrCreate(archive.getUserId());
    }

    @Override
    public void updateArchive(HrArchive archive) {
        updateById(archive);
    }

    @Override
    public void deleteArchive(Long id) {
        removeById(id);
    }

    @Override
    public HrArchive getByUserId(Long userId) {
        return getOne(new LambdaQueryWrapper<HrArchive>().eq(HrArchive::getUserId, userId).last("LIMIT 1"));
    }

    private void fillUser(HrArchive archive) {
        if (archive.getUserId() == null) {
            return;
        }
        SysUser user = userService.getById(archive.getUserId());
        if (user == null) {
            return;
        }
        archive.setUsername(user.getUsername());
        archive.setNickname(user.getNickname());
        archive.setPhone(user.getPhone());
        if (user.getDeptId() != null) {
            SysDept dept = deptService.getById(user.getDeptId());
            if (dept != null) {
                archive.setDeptName(dept.getName());
            }
        }
    }
}
