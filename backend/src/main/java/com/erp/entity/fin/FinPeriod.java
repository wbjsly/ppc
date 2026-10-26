package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会计期间（add-gl-management，design D4：全局单轨 YYYYMM，OPEN/CLOSED）。
 */
@Getter
@Setter
@TableName("erp_fin_period")
public class FinPeriod extends BaseEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    /** YYYYMM */
    private String period;

    /** OPEN / CLOSED */
    private String status;

    private String closeBy;

    private LocalDateTime closeAt;
}
