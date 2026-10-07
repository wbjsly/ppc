package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 沙箱联调用例（SOP-5.5-A 步骤5/6：通过率 100% 才可申请生产放行）。 */
@Getter
@Setter
@TableName("erp_intf_sandbox_case")
public class IntfSandboxCase extends BaseEntity {

    private String contractId;
    private String partnerCode;
    private String caseNo;
    private String caseType;
    private String fieldPath;
    private String expect;
    private String actual;
    private String result;
    private String errorLoc;
    private LocalDateTime runAt;
    private String remark;
}
