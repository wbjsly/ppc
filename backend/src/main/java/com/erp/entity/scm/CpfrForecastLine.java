package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** CPFR 预测行：SKU × 周 + 数据包快照（spec cpfr-collaboration R2）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_forecast_line")
public class CpfrForecastLine extends BaseEntity {

    private String headId;
    private String agreementId;
    private String itemCode;
    private String itemName;
    /** 周偏移 1..HORIZON */
    private Integer weekOffset;
    private BigDecimal forecastQty;
    /** 生成数据包时当前库存快照 */
    private BigDecimal snapshotStock;
    /** 生成数据包时在途 PO 快照 */
    private BigDecimal snapshotOnhandPo;
    /** 安全库存快照 */
    private BigDecimal safetyStock;
}
