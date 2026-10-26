package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 盘点人员分组（add-asset-count，design D1，FR-4.13-5-1）：
 * 两人一组交叉盘点；盘点人员应独立于资产管理（INDEPENDENT_FLAG 留痕）。
 */
@Getter
@Setter
@TableName("erp_eam_count_team_member")
public class EamCountTeamMember extends BaseEntity {

    @TableField("PLAN_ID")
    private String planId;

    @TableField("CNT_NO")
    private String cntNo;

    @TableField("MEMBER_NAME")
    private String memberName;

    @TableField("GROUP_NAME")
    private String groupName;

    /** 独立于资产管理 1=是 */
    @TableField("INDEPENDENT_FLAG")
    private String independentFlag;

    @TableField("REMARK")
    private String remark;
}
