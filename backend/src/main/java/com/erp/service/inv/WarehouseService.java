package com.erp.service.inv;

import com.erp.entity.inv.InvWarehouse;

import java.util.List;

/** 仓库档案（spec warehouse-master：编码不可改、启用/停用状态机、停用不可被新单选用）。 */
public interface WarehouseService {

    /** 列表查询（keyword 匹配编码或名称；status 可空） */
    List<InvWarehouse> list(String keyword, String status);

    InvWarehouse get(String id);

    /** 新建：编码由系统生成（WH- + 4 位流水），创建后不可修改 */
    InvWarehouse create(InvWarehouse warehouse);

    /** 修改：名称/组织/容量/备注可改，编码与状态不变（状态走 enable/disable） */
    InvWarehouse update(InvWarehouse warehouse);

    /** 启用 */
    void enable(String id);

    /** 停用：之后不被新发货单与新预留选用，已存在单据不受影响 */
    void disable(String id);

    /** 下拉用：仅启用仓库 */
    List<InvWarehouse> listEnabled();
}
