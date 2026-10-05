package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * 器具校准记录：PASS 续期 / LIMITED 限定范围 / FAIL 停用并反向追溯。
 */
@Getter
@Setter
@TableName("erp_qms_gauge_calibration")
public class GaugeCalibration extends BaseEntity {
    /** 器具 ID */
    @TableField("GAUGE_ID")
    private String gaugeId;

    /** 计划校准日 */
    @TableField("PLAN_DATE")
    private LocalDate planDate;

    /** 实际校准日 */
    @TableField("CAL_DATE")
    private LocalDate calDate;

    /** PASS / LIMITED / FAIL */
    @TableField("RESULT")
    private String result;

    /** 校准证书编号 */
    @TableField("CERT_NO")
    private String certNo;

    /** 校准机构 */
    @TableField("CAL_ORG")
    private String calOrg;

    /** 下次校准日 */
    @TableField("NEXT_CAL_DATE")
    private LocalDate nextCalDate;

    /** 操作人 */
    @TableField("OPERATOR_NAME")
    private String operatorName;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
