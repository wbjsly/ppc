package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 离职管理（C-4.7-04 交接硬阻断，离场凭证）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_exit")
public class HrExit extends BaseEntity {

    public static final String ST_APPLYING = "APPLYING";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_HANDOVER = "HANDOVER";
    public static final String ST_SETTLED = "SETTLED";
    public static final String ST_EXITED = "EXITED";
    public static final String ST_REJECTED = "REJECTED";

    /** 离职单号 EXT+流水 */
    private String exitNo;
    private String empId;
    private String empName;
    private String empNo;
    private LocalDate quitDate;
    private String reason;
    /** 通知期满足 1（不满足 L4 提示留痕 0） */
    private String noticeOk;
    private String status;
    private String apprId;

    // 交接四类
    private String assetDone;
    private String accountDone;
    private String docDone;
    private String workDone;
    /** 交接明细 JSON（资产卡片/账号/文档/工作项，含接收人与时间） */
    private String handoverJson;

    // 薪酬结算离线登记
    private BigDecimal settleWage;
    private BigDecimal settleAnnual;
    private BigDecimal settleComp;
    private BigDecimal settleNoncomp;
    /** 经济补偿金（N 法定口径自动试算 HR 可覆盖；区别于 settleComp=调休折算） */
    private BigDecimal settleEcomp;
    private String settleNote;
    /** 申请来源 SELF 自助端 / HR 代录（缺省 HR） */
    private String source;
    /** 挽留意见（审批人填写，非阻断留痕） */
    private String retainNote;
    private String settledBy;
    private LocalDateTime settledAt;

    // 社保减员留桩
    private LocalDate reduceDate;
    private String reduceNote;
    /** 1=社保减员手工豁免（离场凭证放行，无 DONE 减员单时用） */
    private String reduceWaived;
    /** 豁免原因+豁免人留痕 */
    private String reduceWaiveNote;

    /** 离场凭证号 EXC+流水 */
    private String exitCertNo;
    private LocalDateTime exitCertAt;
}
