package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.WfApprovalFlow;

import java.util.List;

public interface WfApprovalFlowService extends IService<WfApprovalFlow> {

    List<WfApprovalFlow> listAll();

    WfApprovalFlow getByType(String type);

    /** 启用中的配置；无配置时返回按旧规则推断的默认 */
    WfApprovalFlow requireEnabled(String type);

    void saveFlow(WfApprovalFlow flow);

    List<Long> resolveAssigneeIds(WfApprovalFlow flow);
}
