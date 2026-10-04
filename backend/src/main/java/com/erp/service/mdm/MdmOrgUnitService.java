package com.erp.service.mdm;

import com.erp.entity.mdm.MdmOrgUnit;
import com.erp.entity.mdm.MdmOrgUnitVersion;

import java.util.List;
import java.util.Map;

/**
 * 组织单元主数据业务能力契约（基础数据-组织管理 1.1.4，3 级树 + 六类类型）。
 */
public interface MdmOrgUnitService {

    /** 树形查询，legalEntityId/ouType/keyword/status 可空；返回根节点列表，子节点挂 children */
    List<MdmOrgUnit> tree(String legalEntityId, String ouType, String keyword, String status);

    /** 按 id 查询详情 */
    MdmOrgUnit getById(String id);

    /** 新建：生成 OU-类型码-流水，校验深度与同主体，立即生效，写 V1 快照 */
    MdmOrgUnit create(MdmOrgUnit unit);

    /** 变更：编码不可改、类型与编码前缀一致，改父节点做 DC-06 成环 + 深度双校验，乐观锁，V(N+1) 快照 */
    MdmOrgUnit update(MdmOrgUnit unit);

    /** 停用：有未停用子孙则阻断；C-0-08 下游引用校验桩 */
    void disable(String id);

    /** 指定主体的启用节点选项（含 level/type），供交易四要素与行级权限引用 */
    List<Map<String, String>> options(String legalEntityId);

    /** 版本历史，倒序 */
    List<MdmOrgUnitVersion> versions(String entityId);

    /** 两版本逐字段差异 */
    Map<String, Object> diff(String entityId, int from, int to);
}
