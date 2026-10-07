package com.erp.service.fin;

import com.erp.entity.fin.FinVoucher;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 最小总账凭证骨架（spec gl-voucher，design D2）。
 * 凭证号自动生成（BR-4.6-07）、借贷平衡 L1 硬阻断（FR-4.6-1-4）、
 * 生成即 POSTED、已过账仅可红字冲销（BR-4.6-09）。
 */
public interface GlVoucherService {

    /**
     * 生成凭证（分录统一入口）：科目校验 → 借贷平衡 → 编号 → 头行落库（POSTED）。
     *
     * @param voucherType  ACC/AP/APD/REV/PADJ
     * @param voucherDate  凭证日期（期间 = 其自然月）
     * @param summary      摘要
     * @param sourceType   来源类型 GR/RETURN/MATCH/REVERSE/VMI_TRANSFER/MIGRATION
     * @param sourceDocNo  来源单号（业财锚点）
     * @param supplierId   辅助核算-供应商（可空）
     * @param lines        分录行（accountCode/direction/amount/summary 已填）
     */
    FinVoucher create(String voucherType, LocalDate voucherDate, String summary,
                      String sourceType, String sourceDocNo, String supplierId,
                      List<FinVoucherLineSpec> lines);

    /** 红字冲销（BR-4.6-09）：同科目反向新凭证 + REVERSES_ID 关联；仅 POSTED 可冲销 */
    FinVoucher reverse(String voucherId, String reason, String sourceDocNo);

    /** 来源单据 → 凭证（含行），按生成时间倒序（业财锚点反查） */
    List<Map<String, Object>> listBySource(String sourceType, String sourceDocNo);

    /** 凭证头行详情 */
    Map<String, Object> detail(String voucherId);

    /** 修改摘要（仅 DRAFT；POSTED 422） */
    FinVoucher update(String voucherId, String summary);

    /** 作废（仅 DRAFT 软删；POSTED 422 提示仅可红字冲销） */
    void voidDraft(String voucherId);

    /** 分录行入参（避免服务层依赖持久化对象的填充语义） */
    class FinVoucherLineSpec {
        public String accountCode;
        public String direction;
        public java.math.BigDecimal amount;
        public String summary;

        public static FinVoucherLineSpec of(String accountCode, String direction,
                                            java.math.BigDecimal amount, String summary) {
            FinVoucherLineSpec s = new FinVoucherLineSpec();
            s.accountCode = accountCode;
            s.direction = direction;
            s.amount = amount;
            s.summary = summary;
            return s;
        }
    }
}
