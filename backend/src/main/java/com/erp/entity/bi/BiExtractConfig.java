package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 模板驱动抽取配置（add-data-extract-console design D1/D2，spec bi-extract-console）。
 * 绑定已发布报表模板 + 数据源；全量/增量双策略；SEQ = DAG 层序；
 * LAST_WATERMARK = 断点续抽定位（BR-4.10-13）。
 */
@Getter
@Setter
@TableName("erp_bi_extract_config")
public class BiExtractConfig extends BaseEntity {

    public static final String MODE_FULL = "FULL";
    public static final String MODE_INCREMENTAL = "INCREMENTAL";

    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private String code;
    private String name;
    private String datasourceId;
    private String templateId;
    private String mode;
    private String watermarkField;
    private String lastWatermark;
    private Integer seq;
    private String status;
    private String remark;
}
