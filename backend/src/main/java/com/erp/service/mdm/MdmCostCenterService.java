package com.erp.service.mdm;

import com.erp.entity.mdm.MdmCostCenter;
import com.erp.entity.mdm.MdmCostCenterVersion;

import java.util.List;
import java.util.Map;

/**
 * 成本中心主数据业务能力契约（基础数据-组织管理 1.1.2，3 级树形）。
 */
public interface MdmCostCenterService {

    /** 树形查询，legalEntityId/keyword/status 可空；返回根节点列表，子节点挂 children */
    List<MdmCostCenter> tree(String legalEntityId, String keyword, String status);

    /** 新建：生成 CC-类型码-流水，校验深度，立即生效，写 V1 快照 */
    MdmCostCenter create(MdmCostCenter center);

    /** 变更：编码不可改，改父节点时做 DC-06 成环 + 深度双校验，乐观锁，V(N+1) 快照 */
    MdmCostCenter update(MdmCostCenter center);

    /** 停用：有未停用子孙则阻断；下游引用校验桩（C-4.1-03） */
    void disable(String id);

    /** 设为所属主体的默认中心，同主体旧默认自动清除（FR-4.6-5-4） */
    void setDefault(String id);

    /** 指定主体的启用节点选项，供下游单据引用 */
    List<Map<String, String>> options(String legalEntityId);

    /** 版本历史，倒序 */
    List<MdmCostCenterVersion> versions(String entityId);

    /** 两版本逐字段差异 */
    Map<String, Object> diff(String entityId, int from, int to);
}
