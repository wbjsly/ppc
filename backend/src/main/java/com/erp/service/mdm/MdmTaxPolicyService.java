package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmTaxPolicy;

import java.util.Map;

/**
 * 税收政策台账业务能力契约（税码管理 1.6.2，S-4.1-07 政策侧留痕）。
 */
public interface MdmTaxPolicyService {

    Page<MdmTaxPolicy> page(long current, long size, String keyword);

    MdmTaxPolicy getById(String id);

    /** 登记：必填/生效≥发布日期 422、文号唯一 409 */
    MdmTaxPolicy create(MdmTaxPolicy policy);

    /** 变更：文号锁定 422、日期关系重校验、乐观锁 409，关键字段变更记日志（L5） */
    MdmTaxPolicy update(MdmTaxPolicy policy);

    /** 删除：被税码引用 422（带引用计数）；未引用硬删并记操作日志 */
    void delete(String id);

    /**
     * 关联回链：该文号关联的税码清单 + 各自版本流水（时间倒序）；
     * 无关联返回非空结构含提示语。
     */
    Map<String, Object> taxCodesOf(String id);

    /** 版本快照列表（升序，1.6.2 增强） */
    java.util.List<com.erp.entity.mdm.MdmTaxPolicyVersion> versions(String entityId);

    /** 两版本字段对比（仅变更字段 {field,from,to}） */
    Map<String, Object> diff(String entityId, int from, int to);
}
