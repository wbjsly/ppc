package com.erp.service.mrp;

import com.erp.entity.mrp.MrpOpWcStandard;

import java.util.List;
import java.util.Map;

/**
 * 工序×工作中心工时定额矩阵（change add-routing-management，spec routing-management
 * 「定额矩阵表达工序与工作中心适配」；菜单 5.2.3）。
 * （工序, 工作中心）唯一 = 适配关系唯一表达（无定额行 = 不支持，FR-4.5-3-4 分配带出的前提）；
 * 四类工时（标准/准备/等待/移动）≥ 0；删除被 PUBLISHED 路线行引用的定额 → 422。
 * 角色：ROLE_PROCESS_ENG / ROLE_PROCESS_MGR 维护，写操作放行 ADMIN。
 */
public interface OpWcStandardService {

    /** 列表：按工序编码/工作中心编码筛选（级联下拉数据源），组合升序 */
    List<Map<String, Object>> query(String opCode, String wcCode);

    /** 新增：组合必填、全局唯一（重复 422）、工时 ≥ 0 */
    MrpOpWcStandard create(MrpOpWcStandard in);

    /** 修改：编码不可改，仅四类工时可改（≥ 0） */
    MrpOpWcStandard update(MrpOpWcStandard in);

    /** 删除：被已发布路线行引用 → 422（草稿引用由路线保存卡控兜底，design D3） */
    void delete(String id);
}
