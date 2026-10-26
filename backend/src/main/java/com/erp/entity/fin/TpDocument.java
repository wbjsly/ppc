package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 关联交易定价文档台账（S-4.14-08）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tp_document")
public class TpDocument extends BaseEntity {

    public static final String DOC_LOCAL = "LOCAL";
    public static final String DOC_MASTER = "MASTER";
    public static final String DOC_COUNTRY = "COUNTRY";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_READY = "READY";
    public static final String ST_EXPIRED = "EXPIRED";

    /** 文档编号 TPD+流水 */
    private String docNo;
    private String partyId;
    private String partyName;
    /** LOCAL/MASTER/COUNTRY 三档 */
    private String docType;
    /** 覆盖年度 yyyy */
    private String coverYear;
    /** CUP/CPM/RPM/TNMM/OTHER（独立交易原则方法族） */
    private String pricingMethod;
    private String storagePath;
    /** 备查有效期 */
    private LocalDate validUntil;
    /** DRAFT/READY/EXPIRED */
    private String status;
}
