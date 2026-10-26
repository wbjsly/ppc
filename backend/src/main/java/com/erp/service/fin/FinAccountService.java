package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.FinAccount;

import java.util.Map;

/**
 * 会计科目维护（add-gl-management，8.1.1：只增停不改码——既有编码为 17 域运行依赖）。
 */
public interface FinAccountService {

    /** 分页查询（编码/名称/方向/类别/状态筛选） */
    Page<FinAccount> page(long current, long size, String keyword, String status, String category);

    /** 新增科目（编码唯一、名称必填、方向 DR/CR、类别；AUX_SET 可空） */
    FinAccount create(FinAccount account);

    /** 停用（DISABLED，不得用于新凭证行）/ 启用（ACTIVE） */
    void setStatus(String accountCode, String status);

    /** 科目明细（含辅助核算标记） */
    Map<String, Object> detail(String accountCode);
}
