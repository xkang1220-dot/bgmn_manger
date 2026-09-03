package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_approval_flow")
public class WfApprovalFlow extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 审批类型编码，见 ApprovalTypes */
    private String type;

    private String name;

    /** ALL 会签 / ANY 或签 */
    private String passMode;

    /** 角色编码逗号分隔 */
    private String roleCodes;

    /** 指定用户 ID 逗号分隔 */
    private String userIds;

    /** 超时自动通过小时数，0 关闭 */
    private Integer timeoutHours;

    /** 1启用 0停用 */
    private Integer status;

    private Integer sort;

    private String remark;

    @TableField(exist = false)
    private List<Long> userIdList;

    @TableField(exist = false)
    private List<String> roleCodeList;

    @TableField(exist = false)
    private String passModeLabel;
}
