package com.erp.service.fin;

import com.erp.entity.fin.FinVoucher;

import java.util.List;
import java.util.Map;

/**
 * 科目余额（add-gl-management，design D3：AUX_KEY 拼接 UPSERT + journal 幂等防双计）。
 */
public interface GlBalanceService {

    /** 凭证过账回写余额（逐行 UPSERT 累加；journal 幂等——同一凭证行仅回写一次） */
    void writeBack(FinVoucher voucher);

    /** 辅助维度拼接键（六列 | 分隔，空串=无辅助） */
    String auxKey(String costCenter, String profitCenter, String auxCustomer,
                  String auxSupplier, String auxProject, String auxEmployee, String auxDept);

    /** 余额四栏查询（期初=上期期末，按期间/科目/辅助过滤） */
    List<Map<String, Object>> query(String period, String accountCode, String auxKey);

    /** 指定期间余额行（结账/对账用） */
    List<Map<String, Object>> periodBalances(String period);
}
