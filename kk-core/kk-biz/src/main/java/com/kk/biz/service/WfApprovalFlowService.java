package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.WfApprovalFlow;

import java.util.List;
import java.util.Map;

public interface WfApprovalFlowService extends IService<WfApprovalFlow> {

    List<WfApprovalFlow> listByCompany(Long companyId);

    WfApprovalFlow getByType(String type, Long companyId);

    /** 该公司启用中的配置；无配置则报错 */
    WfApprovalFlow requireEnabled(String type, Long companyId);

    void saveFlow(WfApprovalFlow flow);

    void deleteFlow(Long id);

    /** 将源公司缺失的类型复制到目标公司 */
    int copyFlows(Long fromCompanyId, Long toCompanyId);

    List<Long> resolveAssigneeIds(WfApprovalFlow flow, Long companyId);

    /** 按公司+类型描述当前启用配置（提交前提示用） */
    Map<String, Object> describeEnabled(String type, Long companyId);
}
