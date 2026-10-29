package com.erp.service.system;

import com.erp.entity.system.PositionBaseline;

import java.util.List;

/**
 * 岗位权限基线（add-role-permission-console design D5）：
 * 建号/调岗角色映射的唯一来源（替代 hr_position.ROLES_JSON 读取）；
 * 保存经 PrivilegedRoles L1 硬阻断；岗位创建同步 upsert。
 */
public interface PositionBaselineService {

    /** 岗位基线角色集合（无基线 / 解析失败 → 空集合） */
    List<String> rolesOfPos(String posId);

    /** 读基线行（控制台用），无行返回 null */
    PositionBaseline get(String posId);

    /**
     * 保存基线（upsert）：解析 JSON 数组 → L1 特权校验（违规 422 指明角色）→ 落库。
     * 覆盖所有写入口——控制台编辑与岗位创建同步共用，不给旁路。
     */
    void upsert(String posId, String roleCodesJson, String operator);
}
