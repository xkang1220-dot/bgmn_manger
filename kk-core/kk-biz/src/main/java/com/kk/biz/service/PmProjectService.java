package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectFlow;

public interface PmProjectService extends IService<PmProject> {

    Page<PmProject> pageProjects(long page, long pageSize, String name, Integer status, Long companyId);

    /** 当前用户负责或参与的项目（个人中心）；仅顶层 */
    java.util.List<PmProject> listMine(Long userId);

    /** 下拉用：按当前登录人可见范围返回已生效项目（含小项目，供任务/财务选） */
    java.util.List<PmProject> listVisible();

    /** 财务选项目：返回全部已生效项目（不含待审；含小项目） */
    java.util.List<PmProject> listApproved();

    /** 重大项目下的未删小项目 */
    java.util.List<PmProject> listChildren(Long parentId);

    /**
     * 筛选用：选中重大外壳时展开为其小项目 id 列表；其他项目返回自身。
     * @return null 表示不过滤；非空列表用于 IN 查询（无匹配时含 -1）
     */
    java.util.List<Long> resolveProjectFilterIds(Long projectId);

    /** 项目管理用：无财务权限时会隐藏预算/分成等敏感字段 */
    PmProject getDetail(Long id);

    /** 财务用：始终返回完整预算、资金池、分层配置 */
    PmProject getShareDetail(Long id);

    void createProject(PmProject project);

    void updateProject(PmProject project);

    /**
     * 更新项目；若目标规模需审批且规模有变，则不改 scale，返回待提交的 from/to。
     * @return null 表示已全部落库；非 null 为待审批的规模变更 [fromScale, toScale]
     */
    String[] updateProjectMaybeScaleApproval(PmProject project);

    void applyScaleChange(Long projectId, String toScale, Long approvalId);

    void applyScaleChange(Long projectId, String toScale, Long approvalId, Long operatorId);

    void recordFlow(Long projectId, String action, String fromValue, String toValue, Long approvalId, String remark);

    void recordFlow(Long projectId, String action, String fromValue, String toValue, Long approvalId, String remark, Long operatorId);

    java.util.List<PmProjectFlow> listFlows(Long projectId);

    /** 按公司名拼音首字母生成下一编号，如 XYGS-001 */
    String allocateNextCode(Long companyId);

    /** 任务可选参与人：项目负责人 ∪ 项目成员 */
    java.util.Set<Long> eligibleTaskParticipantIds(Long projectId);

    /** 财务侧保存项目分层（资金池/预算/分成比例） */
    void saveShareConfig(Long projectId, Long poolId, java.math.BigDecimal budget,
                         java.util.List<com.kk.biz.entity.PmProjectMember> members);

    void deleteProject(Long id);

    /** 删除前校验：重大外壳下不可有未删小项目 */
    void assertNoUndeletedChildren(Long projectId);
}
