package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** IP 资产申请状态变更历史（add-ip-management，append-only 留痕）。 */
@Getter
@Setter
@TableName("erp_pm_ip_asset_status_log")
public class PmIpAssetStatusLog extends BaseEntity {

    private String assetId;
    private String fromStatus;
    private String toStatus;
    private String operator;
    private java.time.LocalDateTime operateDate;
    private String remark;
}
