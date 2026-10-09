package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 仓库档案（4.1.1，spec warehouse-master）。
 * 编码系统生成（WH- + 4 位流水）且创建后不可改；状态 1 启用 / 0 停用，
 * 停用仓不被新发货单与新预留选用（在途单据不受影响）。
 */
@Getter
@Setter
@TableName("erp_inv_warehouse")
public class InvWarehouse extends BaseEntity {

    /** 默认仓编码（与 {@link InvStock#DEFAULT_WH}、055 迁移回填值一致） */
    public static final String DEFAULT_WH_CODE = "WH-MAIN";

    /** 仓库编码（系统生成，创建后不可修改） */
    private String whCode;

    private String whName;

    /** 所属组织（组织主数据 ID，DC-02 引用完整性；NULL=存量未关联） */
    private String orgUnit;

    /** 所属法人 LE_CODE（NULL 按 LE-0001，跨法人调拨判定依据，迁移 106） */
    private String leCode;

    /** 仓库类型（WAREHOUSE_TYPE 字典 code，spec warehouse-master 4.1.1 补差距） */
    private String whType;

    /** 1 启用 / 0 停用 */
    private String status;

    /** 容量属性（描述性） */
    private String capacityDesc;

    private String remark;
}
