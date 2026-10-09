package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 仓库区域（4.1.2，spec warehouse-zone-planning，design D1/D3）：
 * 区域编码仓库内唯一且创建后不可改（仓位编号前缀）；B2 模式挂默认属性，
 * 新建仓位时物化继承、可单个覆盖；变更走版本快照（本实体 VER_NO 乐观锁）。
 */
@Getter
@Setter
@TableName("erp_inv_zone")
public class InvZone extends BaseEntity {

    /** 仓库编码（引用 erp_inv_warehouse.WH_CODE，与库存口径一致） */
    private String whCode;

    /** 区域编码（仓库内唯一，创建后不可改） */
    private String zoneCode;

    private String zoneName;

    private Integer sortOrder;

    /** 1 启用 / 0 停用（停用后禁建仓位，既有仓位不级联） */
    private String status;

    // ---- B2 默认属性（新建仓位时继承，字典 code） ----

    private String defBinType;

    private String defTempLevel;

    private String defHazardLevel;

    private String defCleanLevel;

    /** 默认托位容量（NULL=不限） */
    private Integer defCapacityPallet;

    private String remark;
}
