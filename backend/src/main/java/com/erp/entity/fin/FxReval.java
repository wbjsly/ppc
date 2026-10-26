package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 外币重估台账（add-fx-revaluation，spec fx-revaluation，design D5 逐笔粒度）。
 * 一期一笔记录一个货币性项目：外币额 ×（期末汇率 − 记账汇率）= 汇兑差额。
 */
@Getter
@Setter
@TableName("erp_fin_fx_reval")
public class FxReval extends BaseEntity {

    /** 项目类型：AR / AP / BANK */
    public static final String TYPE_AR = "AR";
    public static final String TYPE_AP = "AP";
    public static final String TYPE_BANK = "BANK";

    /** 重估批次号（REVAL-YYYYMM-NNNN，期内唯一） */
    private String revalNo;
    /** 会计期间 YYYYMM */
    private String period;
    private String itemType;
    /** 项目 ID（AR/AP 发票 ID 或银行账户 ID） */
    private String itemId;
    /** 项目单号（发票号/账号） */
    private String itemNo;
    /** 交易对手（客户/供应商；银行账户为空） */
    private String partyId;
    private String currency;
    /** 外币金额（发票未清额/账户外币余额） */
    private BigDecimal fcAmount;
    /** 原记账汇率（单据固化/开户固化） */
    private BigDecimal bookingRate;
    /** 期末重估汇率（期末日中间价） */
    private BigDecimal periodRate;
    /** 汇兑差额 = fcAmount ×（periodRate − bookingRate），正=收益负=损失 */
    private BigDecimal diffAmount;
    /** 关联重估凭证 ID */
    private String voucherId;
    /** 被哪期冲回（冲回批次凭证 ID；未冲回为空） */
    private String reversedBy;
}
