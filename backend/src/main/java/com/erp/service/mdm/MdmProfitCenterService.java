package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmProfitCenter;
import com.erp.entity.mdm.MdmProfitCenterVersion;

import java.util.List;
import java.util.Map;

/**
 * 利润中心主数据业务能力契约（基础数据-组织管理 1.1.3，平铺单级）。
 */
public interface MdmProfitCenterService {

    /** 分页查询，keyword 匹配编码/名称，legalEntityId/status 精确匹配（可空） */
    Page<MdmProfitCenter> page(long current, long size, String keyword, String legalEntityId, String status);

    /** 按 id 查询详情 */
    MdmProfitCenter getById(String id);

    /** 新建：生成 PC-XXXX 流水，立即生效，写 V1 快照 */
    MdmProfitCenter create(MdmProfitCenter center);

    /** 变更：编码不可改，乐观锁，变更后写 V(N+1) 快照 */
    MdmProfitCenter update(MdmProfitCenter center);

    /** 停用：名下有启用成本中心归属则阻断；下游引用校验桩（C-4.1-03） */
    void disable(String id);

    /** 启用主体的选项，供凭证行等下游引用 */
    List<Map<String, String>> options(String legalEntityId);

    /** 版本历史，倒序 */
    List<MdmProfitCenterVersion> versions(String entityId);

    /** 两版本逐字段差异 */
    Map<String, Object> diff(String entityId, int from, int to);
}
