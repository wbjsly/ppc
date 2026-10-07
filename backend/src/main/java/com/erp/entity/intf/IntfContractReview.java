package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 契约评审记录（SOP-5.5-A 步骤2：3 个工作日内完成、意见不可留空、驳回≥3 次关闭）。 */
@Getter
@Setter
@TableName("erp_intf_contract_review")
public class IntfContractReview extends BaseEntity {

    private String contractId;
    private String reviewer;
    private String result;
    private String opinion;
    private LocalDateTime dueAt;
    private LocalDateTime remindAt;
    private LocalDateTime reviewAt;
    private String remark;
}
