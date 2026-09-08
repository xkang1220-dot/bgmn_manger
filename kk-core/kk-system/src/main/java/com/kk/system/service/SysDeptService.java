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

    /** 解析部门所属公司（顶层部门 id） */
    Long resolveCompanyId(Long deptId);

    /** 列出所有公司（parent_id=0） */
    List<SysDept> listCompanies();
}
