package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 职责分离预检（add-permission-approval-flow design D4，SOP-5.6-B step2）：
 * 沙盘只读实时预检 + 提交时强制复检（结果序列化为申请单 SOD_SNAPSHOT_JSON 快照）。
 */
public interface SoDCheckService {

    /**
     * 预检：超基线（目标 − 岗位基线）、SoD 冲突（目标×目标 ∪ 目标×现有 命中互斥矩阵）、
     * 申请人现有角色。
     *
     * @param targetUserId 被检用户（空 = 当前登录用户）
     * @param roleCodes    目标角色集合
     * @return { baseline:[...], beyondBaseline:[...], existing:[...],
     *           conflicts:[{roleA, roleB, remark}] }
     */
    Map<String, Object> check(String targetUserId, List<String> roleCodes);

    /** 全部互斥规则（规范化 A<B） */
    List<Map<String, Object>> listRules();

    /** 新增互斥规则（自动规范化 A<B；重复 422） */
    void addRule(String roleA, String roleB, String remark);

    /** 删除规则 */
    void deleteRule(String id);
}
