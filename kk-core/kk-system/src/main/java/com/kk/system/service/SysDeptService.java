package com.kk.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.system.entity.SysDept;

import java.util.List;

public interface SysDeptService extends IService<SysDept> {

    List<SysDept> tree();

    void createDept(SysDept dept);

    void updateDept(SysDept dept);

    void deleteDept(Long id);

    List<Long> listChildIds(Long deptId);
}
