package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报工产出行（change add-reporting-management，spec shop-floor-reporting 超产分级 +
 * in-process-quality 分级 NCR，design D1/D6，迁移 123）。良率工序级累计的分子分母来源
 * （design D2：累计投入 = Σ(GOOD+NG)，累计合格 = Σ GOOD，CONFIRMED 单口径）。
 */
@Getter
@Setter
@TableName("erp_mrp_output")
public class MrpOutput extends BaseEntity {

    private String rptId;
    private String moId;
    private Integer opSeq;
    /** 合格产出（良率分子） */
    private BigDecimal goodQty;
    /** 不合格产出 */
    private BigDecimal ngQty;
    /** SCRAP 报废 / REWORK 返工 / CONCESSION 让步接收（BR-4.5-26） */
    private String ngDispose;
    /** 关联生产 NCR（refType=WORKORDER；小额报废域内处置为 NULL） */
    private String ncrId;
    /** 域内直接处置 1（报废 < NCR_AUTO_QTY，design D6 分级豁免） */
    private String directDisposeFlag;
    private LocalDateTime outputAt;
}
