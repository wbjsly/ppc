package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 比价结果快照（2.2.4，BR-4.2-14 双轨留存 + 审计留痕，change add-price-comparison-matrix design D4）。
 * RFQ 维度唯一（UK_ANALYSIS_RFQ），重复定标覆盖更新；JSON 自描述（schemaVersion）。
 * <p>偏差 D3 —— 归档形态为「系统快照 JSON + 前端 CSV/打印导出」，非后端生成正式文档文件
 * （全项目无文档生成基础设施，正式文件生成属独立导出基础设施变更）。</p>
 */
@Getter
@Setter
@TableName("erp_proc_analysis_snapshot")
public class AnalysisSnapshot extends BaseEntity {

    private String rfqId;
    private String rfqNo;

    /** 快照 JSON：四维权重（含锁定质量维）、全部报价原始/谈判双轨价、异常确认与剔除记录、均值与偏离率、分析表与定标结论 */
    @TableField("SNAPSHOT_JSON")
    private String snapshotJson;

    /** 冗余检索列（列表展示免解析 JSON） */
    @TableField("ANALYSIS_NO")
    private String analysisNo;

    @TableField("AWARD_SUPPLIER_ID")
    private String awardSupplierId;

    @TableField("AWARD_PRICE")
    private BigDecimal awardPrice;
}
