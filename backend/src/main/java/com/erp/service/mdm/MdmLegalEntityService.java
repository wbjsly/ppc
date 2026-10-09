package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.entity.mdm.MdmLegalEntityVersion;

import java.util.List;
import java.util.Map;

/**
 * 法人主体主数据业务能力契约（基础数据-组织管理 1.1.1）。
 */
public interface MdmLegalEntityService {

    /** 分页查询，keyword 匹配编码/名称，status 精确匹配（可空） */
    Page<MdmLegalEntity> page(long current, long size, String keyword, String status);

    /** 按 id 查询详情 */
    MdmLegalEntity getById(String id);

    /** 新建：生成 LE- 编码，立即生效，写 V1 快照 */
    MdmLegalEntity create(MdmLegalEntity entity);

    /** 变更：编码不可改，乐观锁，变更前写 V(N+1) 快照 */
    MdmLegalEntity update(MdmLegalEntity entity);

    /** 停用（软删除语义，禁止物理删除），停用前校验下游引用 */
    void disable(String id);

    /** 启用中的主体选项，供下游单据引用 legal_entity_id 下拉取数 */
    List<Map<String, String>> options();

    /** 版本历史，倒序 */
    List<MdmLegalEntityVersion> versions(String entityId);

    /** 两个版本逐字段差异 */
    Map<String, Object> diff(String entityId, int from, int to);
}
