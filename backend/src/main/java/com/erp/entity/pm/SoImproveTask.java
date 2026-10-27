package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 服务工单低分改进任务（add-service-order，BR-4.8-06 ≤2 分自动生成）。 */
@Getter
@Setter
@TableName("erp_pm_service_improve_task")
public class SoImproveTask extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_CLOSED = "CLOSED";

    /** 来源工单 */
    private String orderId;
    private String content;
    private String ownerId;
    private String ownerName;
    /** 期限=生成+10 工作日 */
    private LocalDateTime dueDate;
    /** PENDING|PROCESSING|CLOSED */
    private String status;
    /** 1=已逾期升级销售经理 */
    private String escalatedSales;
    private String closeBy;
    private LocalDateTime closeDate;
}
