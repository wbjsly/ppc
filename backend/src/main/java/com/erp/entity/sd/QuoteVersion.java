package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 报价版本快照（FR-4.3-1-7）：创建/提交/发布/修订/驳回/转化全留痕，历史版本可查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_quote_version")
public class QuoteVersion extends BaseEntity {

    public static final String OP_CREATE = "CREATE";
    public static final String OP_SUBMIT = "SUBMIT";
    public static final String OP_PUBLISH = "PUBLISH";
    public static final String OP_REVISE = "REVISE";
    public static final String OP_REJECT = "REJECT";
    public static final String OP_CONVERT = "CONVERT";

    private String quoteId;
    private Integer versionNo;
    private String opType;
    /** 头+行快照 JSON */
    private String snapshotJson;
    private String operatorId;
    private LocalDateTime operateAt;
    private String remark;
}
