package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 出库改批偏离台账（4.6.1 偏离监控，spec outbound-strategy 改批偏离留痕；C-4.4-08 非波次首期）：
 * 用户改写系统推荐批次时记录「推荐值 vs 实际值 + 原因」，仅留痕不走审批（偏差 D1）。
 */
@Getter
@Setter
@TableName("erp_inv_batch_deviation")
public class InvBatchDeviation extends BaseEntity {

    /** MATERIAL_ISSUE（领料行级改批）/ PICK_RECOMMEND（4.6.3 推荐确认改写） */
    private String srcDocType;

    private String srcDocNo;

    private Integer lineNo;

    private String itemCode;

    private String warehouseCode;

    /** 系统推荐批次（无推荐值时 NULL） */
    private String recommendedBatch;

    /** 用户实际指定批次 */
    private String actualBatch;

    /** 偏离原因（必填，空则 422） */
    private String reason;
}
