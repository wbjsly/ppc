package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 结项检查项行（自动项落数值证据快照，审批后冻结留档）。 */
@Getter
@Setter
@TableName("erp_pm_closure_check_item")
public class ClosureCheckItem extends BaseEntity {

    public static final String R_PASS = "PASS";
    public static final String R_FAIL = "FAIL";
    public static final String R_PENDING = "PENDING";

    /** 六个固定检查项 key（S-4.8-07 业务逻辑 6） */
    public static final String K_WBS = "WBS";
    public static final String K_MS = "MS";
    public static final String K_ACCEPT = "ACCEPT";
    public static final String K_COST = "COST";
    public static final String K_IP = "IP";
    public static final String K_DOC = "DOC";

    private String checkId;
    private Integer lineNo;
    /** WBS|MS|ACCEPT|COST|IP|DOC|CUSTOM_x */
    private String itemKey;
    private String itemName;
    /** 强制项 1（C-4.8-05 硬阻断）|非强制 0（遗留通道） */
    private String mandatoryFlag;
    /** 自动校验 1 |人工确认 0 */
    private String autoFlag;
    /** PASS|FAIL|PENDING */
    private String result;
    /** 数值证据快照（非布尔：doneRate/openMs/deviation/missingIp…） */
    private String evidenceJson;
    /** 快照时点（重新校验刷新） */
    private LocalDateTime evidenceAt;
    /** 人工项凭证附件（BIZ_TYPE=PROJECT_CLOSURE，勾 PASS 必传 ≥1） */
    private String attachIds;
    private String confirmBy;
    private LocalDateTime confirmDate;
    private String remark;
}
