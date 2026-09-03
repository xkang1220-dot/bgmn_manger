package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.PmProject;

public interface PmProjectService extends IService<PmProject> {

    Page<PmProject> pageProjects(long page, long pageSize, String name, Integer status);

    /** 当前用户负责或参与的项目（个人中心） */
    java.util.List<PmProject> listMine(Long userId);

    /** 项目管理用：无财务权限时会隐藏预算/分成等敏感字段 */
    PmProject getDetail(Long id);

    /** 财务用：始终返回完整预算、资金池、分层配置 */
    PmProject getShareDetail(Long id);

    void createProject(PmProject project);

    void updateProject(PmProject project);

    /** 财务侧保存项目分层（资金池/预算/分成比例） */
    void saveShareConfig(Long projectId, Long poolId, java.math.BigDecimal budget,
                         java.util.List<com.kk.biz.entity.PmProjectMember> members);

    void deleteProject(Long id);
}
