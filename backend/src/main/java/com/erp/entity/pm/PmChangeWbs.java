package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 项目变更受影响 WBS 集（add-project-change，C-4.8-09 拦截依据）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_change_wbs")
public class PmChangeWbs extends BaseEntity {

    private String changeId;
    private String wbsId;
}
