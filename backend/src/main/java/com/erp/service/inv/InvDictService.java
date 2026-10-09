package com.erp.service.inv;

import com.erp.entity.mdm.MdmItemDict;

import java.util.List;

/**
 * 仓库域字典维护（4.1.3，spec warehouse-attribute-config，design D5）：
 * 五类白名单（WAREHOUSE_TYPE/BIN_TYPE/TEMP_LEVEL/HAZARD_LEVEL/CLEAN_LEVEL），
 * 写权限 ROLE_WAREHOUSE（与 MDM 写权限面分离），DICT_CODE 创建后不可改。
 */
public interface InvDictService {

    /** 按类型查条目（启用+停用，供管理页） */
    List<MdmItemDict> list(String dictType, String status);

    /** 启用条目（下拉消费） */
    List<MdmItemDict> listActive(String dictType);

    /** 新建条目（类型白名单 422、类型内编码唯一 409、编码创建后不可改） */
    MdmItemDict create(MdmItemDict item);

    /** 变更条目（编码锁定 422，仅名称/排序可改） */
    MdmItemDict update(MdmItemDict item);

    /** 启用 */
    void enable(String id);

    /** 停用（既有引用不受影响） */
    void disable(String id);
}
