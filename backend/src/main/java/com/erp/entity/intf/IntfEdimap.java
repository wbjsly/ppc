package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** EDI-ERP 字段映射规则（BR-4.9-25：按伙伴 × 报文类型，缺失即 L1 硬阻断）。 */
@Getter
@Setter
@TableName("erp_intf_edimap")
public class IntfEdimap extends BaseEntity {

    private String partnerCode;
    private String msgType;
    /** 字段改名 / 日期归一 / 单位换算 / 默认值 */
    private String ruleJson;
    private String status;
    private String version;
    private String remark;
}
