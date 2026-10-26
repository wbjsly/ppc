package com.erp.service.fin;

import com.erp.entity.fin.InputInvoice;

import java.util.List;
import java.util.Map;

/**
 * 进项发票管理（M9-2-2，spec invoice-management，FR-4.14-3）。
 * 登记（三单匹配自动判定）→ 查验留痕 → 认证登记（C-4.14-10/07/11）→ 批量进项凭证（TAXIN 锚）。
 */
public interface InputInvoiceService {

    /**
     * 进项发票登记（FR-4.14-3-1/3，D2 三单匹配）：
     * 供应商一致 L1（不符 422）；金额 vs PO 未开票余额差异率 > TOLERANCE_DEFAULT → DIFF（阻断认证）；
     * 容差内 → MATCHED。票号唯一。
     */
    Map<String, Object> register(Map<String, Object> payload);

    /** 进项列表（筛选：认证状态/匹配状态/供应商/期间） */
    List<InputInvoice> list(String certStatus, String matchStatus, String supplierId, String period);

    /** 查验留痕登记（外部查验 OOS，登记即回传） */
    InputInvoice inspect(String id, String method, String result);

    /**
     * 认证登记（FR-4.14-3-4，D3 三卡控）：已认证 422 回显原日期（C-4.14-10）；
     * DIFF 阻断（需采购确认）；超 INPUT_TAX_CERTIFY_DAYS 自动 NOT_DEDUCTIBLE（C-4.14-07）；
     * CERT_PERIOD=认证日 YYYYMM 锁定（C-4.14-11）。
     */
    InputInvoice certify(String id, String certDate);

    /**
     * 批量生成本期进项凭证（FR-4.14-3-5，D4）：可抵扣 借222101/贷2202；
     * 不可抵扣 借660299/贷2202；DRAFT 三态+TAXIN 锚+幂等回写。
     */
    Map<String, Object> generateVouchers(String period);

    /** 临期未认证预警清单（C-4.14-07 预警，视图语义） */
    List<InputInvoice> nearingExpiry();
}
