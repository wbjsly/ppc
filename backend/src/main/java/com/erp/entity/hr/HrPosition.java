package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** HR 轻量岗位（角色映射/编制，design D1）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_position")
public class HrPosition extends BaseEntity {
    /** 岗位编号 POS+流水 */
    private String posNo;
    private String name;
    private String deptId;
    /** 岗位角色映射 JSON 数组（账号联动用） */
    private String rolesJson;
    /** 关联职级（带宽经职级间接获得，add-org-management） */
    private String gradeId;
    /** 岗位说明书要点 JSON（职责/考核标准） */
    private String jdJson;
    /** 编制数（空=不限，调岗满编校验） */
    private Integer headcount;
    /** 1 启用 / 0 停用 */
    private String status;
}
