package com.erp.service.hr;

import com.erp.entity.hr.HrEmployeeCert;

import java.util.List;
import java.util.Map;

/**
 * 人员技能矩阵（skill-matrix）：CERT/TRAIN 双来源同步 + 按技能查人。
 * 同人同技能 UK 唯一，事件 upsert；失效按来源隔离（证书失效只影响 CERT 来源行）。
 */
public interface HrSkillMatrixService {

    /** 证书审核通过：upsert（CERT 来源，ACTIVE，等级取类别默认） */
    void syncFromCert(HrEmployeeCert cert);

    /** 证书失效/归档：仅该证书写入（sourceDoc=cert.id 且来源 CERT）的行置 EXPIRED */
    void expireFromCert(HrEmployeeCert cert);

    /** 培训完成：upsert（TRAIN 来源，ACTIVE） */
    void syncFromTraining(String empId, String skillCode, String skillLevel, String planId);

    /** 培训改判不达线：仅该计划写入（sourceDoc=planId 且来源 TRAIN）的行置 EXPIRED */
    void expireFromTraining(String empId, String skillCode, String planId);

    /** 按技能查有效（ACTIVE）且在职人员；level 精确过滤可空 */
    List<Map<String, Object>> queryBySkill(String skillCode, String level);
}
