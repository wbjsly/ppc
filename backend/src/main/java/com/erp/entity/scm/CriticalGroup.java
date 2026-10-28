package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 关键物料组白名单（add-partner-onboarding，表 erp_scm_critical_group，
 * BR-4.9-10 风险判定输入：物料组 ∈ 白名单 → 关键物料 → 强制三级审核）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_scm_critical_group")
public class CriticalGroup extends BaseEntity {

    /** 物料组编码（erp_mdm_item.MATERIAL_GROUP，UK） */
    private String materialGroup;

    private String remark;
}
