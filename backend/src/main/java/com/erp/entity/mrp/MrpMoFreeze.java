package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 良率冻结台账（change add-reporting-management，spec in-process-quality
 * 「良率超差分级冻结」+「冻结解除与通知闭环」，design D4，迁移 123）。
 * 活动行模式仿 erp_mrp_reservation：ACTIVE_FLAG 活动=1 关闭=NULL，
 * UNIQUE(MO_ID, ACTIVE_FLAG) 保证同工单至多一条活动冻结（冲销回拨/NCR 关闭可多轮解冻）。
 * SCOPE：MO（默认，仅冻该工单后续派工——偏差 D6 不误伤急单）/ WC（规格字面冻全线）。
 */
@Getter
@Setter
@TableName("erp_mrp_mo_freeze")
public class MrpMoFreeze extends BaseEntity {

    public static final String SCOPE_MO = "MO";
    public static final String SCOPE_WC = "WC";
    public static final String REASON_YIELD = "YIELD";

    private String moId;
    private String moNo;
    /** SCOPE=WC 时的工作中心 */
    private String wcCode;
    private String scope;
    private String reason;
    /** 触发时实际良率（%） */
    private BigDecimal yieldValue;
    /** 触发时良率偏差（%） */
    private BigDecimal deviationValue;
    /** 关联 NCR（≥Major 升 WC 级） */
    private String ncrId;
    private String frozenBy;
    private LocalDateTime frozenAt;
    /** 活动=1 关闭=NULL（唯一索引多 NULL 技巧） */
    private String activeFlag;
    private String releasedBy;
    private LocalDateTime releasedAt;
    private String releaseReason;
    /** 首次通知接收方快照（解冻通知原接收人幂等，design D4） */
    private String noticeIds;
}
