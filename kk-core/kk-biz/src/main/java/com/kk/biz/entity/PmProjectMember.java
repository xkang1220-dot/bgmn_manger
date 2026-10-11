package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_project_member")
public class PmProjectMember extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long userId;

    /** 项目职责标识（短文本）；财务分层场景也可复用 */
    private String layer;

    private BigDecimal percent;

    private String remark;

    @TableField(exist = false)
    private String userName;

    @TableField(exist = false)
    private String nickname;

    @TableField(exist = false)
    private Integer taskCount;

    @TableField(exist = false)
    private List<String> taskTitles;
}
