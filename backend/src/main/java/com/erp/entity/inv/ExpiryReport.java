package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日效期预警报告（4.10.1，spec expiry-management 需求①，FR-4.4-3-6；迁移 113）。
 * REPORT_DATE 唯一，重复生成覆盖（幂等）；黄橙红三级 + 锁定计数 + 明细/增减快照。
 */
@Getter
@Setter
@TableName("erp_inv_expiry_report")
public class ExpiryReport extends BaseEntity {

    /** 报告日（每日一份） */
    private LocalDate reportDate;

    /** 黄色预警批次数（剩余 ≤90 天） */
    private Integer yellowCnt;

    /** 橙色（≤60） */
    private Integer orangeCnt;

    /** 红色（≤30） */
    private Integer redCnt;

    /** 锁定批次数 */
    private Integer lockedCnt;

    /** 三级预警明细快照 JSON */
    private String detailJson;

    /** 较前报新增清单 JSON */
    private String newJson;

    /** 较前报解除清单 JSON */
    private String clearedJson;

    /** 1=首日无前报基线 */
    private String noBaseline;

    private LocalDateTime genAt;

    private String genBy;

    private String remark;
}
