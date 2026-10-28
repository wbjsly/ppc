package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** CPFR 预测版本头（spec cpfr-collaboration R2）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_forecast_head")
public class CpfrForecastHead extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PUSHED = "PUSHED";
    public static final String ST_CLOSED = "CLOSED";

    /** 周期标识 2026W44 / 202610 */
    private String periodTag;
    private String status;
    /** 推送幂等键 */
    private String pushIdempotentKey;
    private LocalDateTime pushedAt;
    private String remark;
}
