package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 出入库业务类型配置（spec stock-doc-type，迁移 104）。
 * TYPE_CODE 创建后不可改（流水引用稳定性）；配置只承载注册/展示语义，
 * 业务硬规则留域服务与引擎钩子（proposal 偏差 D2，防改配置绕过校验）。
 */
@Getter
@Setter
@TableName("erp_inv_doc_type")
public class InvDocType extends BaseEntity {

    public static final String DIR_IN = "IN";
    public static final String DIR_OUT = "OUT";

    public static final String ALLOC_AUTO = "AUTO_FIFO";
    public static final String ALLOC_MANUAL = "MANUAL";

    private String typeCode;
    /** IN / OUT */
    private String direction;
    private String typeName;
    /** 流水编号前缀（本期统一 TX） */
    private String flowPrefix;
    /** 缺省分配：AUTO_FIFO / MANUAL（请求级可覆盖） */
    private String defaultAlloc;
    /** 1=批次必填+联动建档 */
    private Integer needBatch;
    /** 1=序列判重强制 */
    private Integer needSerial;
    /** 1=启用 / 0=停用（停用拒新过账） */
    private Integer enabled;
    private String remark;
}
