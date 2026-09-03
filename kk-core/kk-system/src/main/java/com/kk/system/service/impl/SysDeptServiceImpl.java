package com.kk.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.mapper.SysDeptMapper;
import com.kk.system.mapper.SysUserMapper;
import com.kk.system.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements SysDeptService {

    private final SysUserMapper userMapper;

    @Override
    public List<SysDept> tree() {
        List<SysDept> depts = list(new LambdaQueryWrapper<SysDept>().orderByAsc(SysDept::getSort).orderByAsc(SysDept::getId));
        return buildTree(depts);
    }

    @Override
    public void createDept(SysDept dept) {
        if (dept.getParentId() == null) {
            dept.setParentId(0L);
        }
        if (dept.getStatus() == null) {
            dept.setStatus(1);
        }
        save(dept);
    }

    @Override
    public void updateDept(SysDept dept) {
        if (dept.getId().equals(dept.getParentId())) {
            throw new BusinessException("上级部门不能是自己");
        }
        updateById(dept);
    }

    @Override
    public void deleteDept(Long id) {
        long children = count(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, id));
        if (children > 0) {
            throw new BusinessException("请先删除子部门");
        }
        Long users = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, id));
        if (users > 0) {
            throw new BusinessException("部门下仍有人员，无法删除");
        }
        removeById(id);
    }

    @Override
    public List<Long> listChildIds(Long deptId) {
        List<SysDept> all = list();
        List<Long> ids = new ArrayList<>();
        ids.add(deptId);
        collect(all, deptId, ids);
        return ids;
    }

    private void collect(List<SysDept> all, Long parentId, List<Long> ids) {
        for (SysDept dept : all) {
            if (parentId.equals(dept.getParentId())) {
                ids.add(dept.getId());
                collect(all, dept.getId(), ids);
            }
        }
    }

    private List<SysDept> buildTree(List<SysDept> depts) {
        Map<Long, List<SysDept>> grouped = depts.stream()
                .collect(Collectors.groupingBy(d -> d.getParentId() == null ? 0L : d.getParentId()));
        for (SysDept dept : depts) {
            List<SysDept> children = grouped.getOrDefault(dept.getId(), new ArrayList<>());
            children.sort(Comparator.comparing(SysDept::getSort, Comparator.nullsLast(Integer::compareTo)));
            dept.setChildren(children);
        }
        return grouped.getOrDefault(0L, new ArrayList<>());
    }
}
