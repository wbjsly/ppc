package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 波次分配行（4.8.1，spec wave-management 波次级分配 / C-4.4-08 行级锁）：
 * 波次级统一分配视图（发货行为过账事实源，双写 design D1）；LOCK_FLAG=1 改批审批中冻结拣货。
 */
@Getter
@Setter
@TableName("erp_inv_wave_line")
public class InvWaveLine extends BaseEntity {

    public static final String FIELD_BATCH = "BATCH";
    public static final String FIELD_BIN = "BIN";

    private String waveId;

    private String shipId;

    private String shipLineId;

    private Integer lineNo;

    private String itemCode;

    private String itemName;

    private String warehouseCode;

    private String batchNo;

    private String binCode;

    private BigDecimal qty;

    /** 改批锁 1/0（C-4.4-08）：ALLOCATED 前置 = 无 LOCK_FLAG='1' 行 */
    private String lockFlag;
}
