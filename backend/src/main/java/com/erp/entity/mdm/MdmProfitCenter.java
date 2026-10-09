package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 利润中心（基础数据-组织管理 1.1.3，表 erp_mdm_profit_center，平铺单级）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_profit_center")
public class MdmProfitCenter extends BaseEntity {

    /** PC- + 4 位流水，跨主体全局唯一，创建后不可修改（C-4.1-01） */
    private String pcCode;

    private String pcName;

    /** 隶属法人主体 */
    private String legalEntityId;

    private String ownerName;

    private String remark;

    /** 1 启用 / 0 停用（C-4.1-03 仅停用，禁止物理删除） */
    private String status;
}
