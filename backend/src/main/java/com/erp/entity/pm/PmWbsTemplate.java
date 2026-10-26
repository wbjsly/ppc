package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** WBS 模板（project-initiation，BR-4.8-13；维护入口 11.3.2 页内 Tab）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_wbs_template")
public class PmWbsTemplate extends BaseEntity {

    private String templateNo;
    private String name;
    /** 研发/实施/运维 */
    private String projType;
    /** 1 启用 / 0 停用 */
    private String status;
    /** 三层结构 [{n,l,d,h,c:[...]}] 短键 JSON */
    private String templateJson;
}
