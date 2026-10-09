package com.erp.service.inv;

import com.erp.entity.inv.InvDocType;

import java.util.List;

/**
 * 出入库业务类型配置（spec stock-doc-type）。
 * 读：仅需认证；写：限 ROLE_ADMIN（服务层强制，TYPE_CODE 创建后锁定）。
 */
public interface StockDocTypeService {

    /** 全部类型（含停用，携 ENABLED 标记） */
    List<InvDocType> list();

    /** 按码取类型（停用也返回，调用方按 enabled 分支；不存在返回 null） */
    InvDocType getByCode(String typeCode);

    /** 新增（TYPE_CODE 唯一 409、字段校验 422、仅 ADMIN） */
    InvDocType create(InvDocType req);

    /** 修改（TYPE_CODE 不可改 422、枚举校验 422、仅 ADMIN） */
    InvDocType update(InvDocType req);

    /** 启停（仅 ADMIN；停用后引擎拒新过账） */
    InvDocType setEnabled(String id, boolean enabled);
}
