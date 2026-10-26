package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 间接成本分摊明细（add-cost-collection-freeze；同 PERIOD+POOL 幂等）。 */
@Getter
@Setter
@TableName("erp_pm_alloc_detail")
public class PmAllocDetail extends BaseEntity {

    /** yyyy-MM */
    private String period;
    private String poolId;
    private String projectId;
    /** 间接分摊落项目级（WBS 空） */
    private String wbsId;
    /** 占比基数快照 */
    private java.math.BigDecimal allocBase;
    /** 占比快照 */
    private java.math.BigDecimal allocRate;
    private java.math.BigDecimal amount;
    private String voucherId;
    /** →erp_pm_cost_collect */
    private String collectId;
}
