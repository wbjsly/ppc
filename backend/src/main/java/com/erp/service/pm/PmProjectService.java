package com.erp.service.pm;

import com.erp.entity.pm.PmProject;

import java.util.List;
import java.util.Map;

/** 项目立项（project-initiation，FR-4.8-2-1；11.3.1）。 */
public interface PmProjectService {

    /** 创建项目（DRAFT，无编号）：双路径门槛 + 项目经理校验 */
    PmProject create(Map<String, Object> payload);

    /** 更新（仅 DRAFT 可改；EXT/INTERNAL 门槛字段重校验） */
    PmProject update(String id, Map<String, Object> payload);

    /** 列表：PM 角色仅自己担任项目经理的项目，PMO/ADMIN 全量 */
    List<Map<String, Object>> list(String status, String projType, String keyword);

    /** 详情（含 WBS 概览与基线信息） */
    Map<String, Object> detail(String id);

    /** 逻辑删（仅 DRAFT） */
    void delete(String id);
}
