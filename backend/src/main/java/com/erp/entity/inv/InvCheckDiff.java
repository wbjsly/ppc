package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 通用差异单（4.7.4，spec picking-review 差异单台账与闭环）：
 * DIFF_TYPE=PICK 拣货差异（本组）/ COUNT 盘点差异（4.11 写入，默认视图隔离）；
 * 四分类 DIFF_KIND=QTY/BATCH/SERIAL/QUALITY；PENDING→RESOLVED（处理说明必填，本组不挂审批=偏差 D3）。
 * 关联任务存在 PENDING 行 → 全部出库过账入口 422（BR-4.4-29 门闩）。
 */
@Getter
@Setter
@TableName("erp_inv_check_diff")
public class InvCheckDiff extends BaseEntity {

    public static final String T_PICK = "PICK";
    public static final String T_COUNT = "COUNT";
    /** 分播复核差异（spec wave-management 4.8.2；srcDocNo=订单号，门闩按订单拦截） */
    public static final String T_WAVE_SORT = "WAVE_SORT";

    /** 关联波次（DIFF_TYPE=WAVE_SORT 时非空，spec wave-management） */
    private String waveId;

    /** 关联波次单据归属行 */
    private String waveDocId;

    public static final String K_QTY = "QTY";
    public static final String K_BATCH = "BATCH";
    public static final String K_SERIAL = "SERIAL";
    public static final String K_QUALITY = "QUALITY";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_RESOLVED = "RESOLVED";

    private String diffNo;

    private String diffType;

    private String srcTaskId;

    private String srcDocType;

    private String srcDocNo;

    private Integer lineNo;

    private String itemCode;

    private String warehouseCode;

    private String batchNo;

    private String diffKind;

    private BigDecimal expectQty;

    private BigDecimal actualQty;

    private BigDecimal deltaQty;

    /** 差异说明（登记必填） */
    private String diffNote;

    private String status;

    private String resolveNote;

    private String resolveBy;

    private LocalDateTime resolveAt;
}
