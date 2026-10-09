package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 序列号台账（4.2.2，spec serial-master，design D1/D3）：
 * 序列号全局唯一（跨物料，录入侧直接暴露串码 BR-4.11-15）且创建后不可改；
 * 状态机 IN_STOCK → OUT/SCRAPPED/FROZEN、FROZEN → IN_STOCK，OUT/SCRAPPED 终态；
 * 流转经 SerialServiceImpl.transition 单点强制并写 InvSerialLog 留痕。
 */
@Getter
@Setter
@TableName("erp_inv_serial")
public class InvSerial extends BaseEntity {

    public static final String ST_IN_STOCK = "IN_STOCK";
    public static final String ST_OUT = "OUT";
    public static final String ST_FROZEN = "FROZEN";
    public static final String ST_SCRAPPED = "SCRAPPED";

    /** 全局唯一（UK_INV_SERIAL），创建后不可改 */
    private String serialNo;

    private String itemCode;

    private String itemName;

    /** 批次号（可空，不强制存在于批次台账，design D1） */
    private String batchNo;

    /** 状态机状态 */
    private String status;

    /** 来源单据号 */
    private String sourceDocNo;

    /** 存放位置备注 */
    private String locationRemark;

    private String remark;
}
