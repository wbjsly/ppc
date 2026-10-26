package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 成本偏差预警（add-cost-collection-freeze，C-4.8-02 双阈值）。 */
@Getter
@Setter
@TableName("erp_pm_cost_alert")
public class PmCostAlert extends BaseEntity {

    private String projectId;
    /** YELLOW|RED */
    private String level;
    /** 触发时偏差率快照（%） */
    private java.math.BigDecimal deviationRate;
    /** 触发时 PV 快照 */
    private java.math.BigDecimal pv;
    /** 触发时实际累计快照 */
    private java.math.BigDecimal actual;
    private java.time.LocalDate alertDate;
    private String handledFlag;
    private String handledBy;
    private String handledNote;
    private java.time.LocalDateTime handledAt;
}
