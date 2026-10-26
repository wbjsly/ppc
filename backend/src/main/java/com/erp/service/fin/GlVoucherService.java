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

    /**
     * 生成凭证（可指定落 DRAFT）：全部校验同上；draft=true 时状态落 DRAFT 不过账，
     * 供「会计期间已关 → 待下期过账」路径使用（spec completion-cost-voucher 期间卡控；
     * gl-voucher DRAFT 为既有状态：可作废、不可过账态修改）。
     */
    FinVoucher create(String voucherType, LocalDate voucherDate, String summary,
                      String sourceType, String sourceDocNo, String supplierId,
                      List<FinVoucherLineSpec> lines, boolean draft);

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

    // ---- add-gl-management：手工凭证三态（SOURCE_TYPE=MANUAL，DRAFT/AUDITED/POSTED）----

    /** 手工凭证保存（DRAFT）：C-4.6-01 平衡/科目 ACTIVE/零金额/C-4.6-06 法人币种全量列示 */
    FinVoucher createManual(FinVoucher header, List<com.erp.entity.fin.FinVoucherLine> lines);

    /** 手工凭证分页（状态筛选，MANUAL only） */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<FinVoucher> manualPage(
            long current, long size, String status, String keyword);

    /** 审核通过（DRAFT→AUDITED；C-4.6-07 制单≠审核人硬卡控） */
    FinVoucher audit(String voucherId);

    /** 审核驳回（DRAFT 态留因，不推进状态） */
    void reject(String voucherId, String reason);

    /** 过账（AUDITED→POSTED + 写余额；期间 CLOSED 阻断 FR-4.6-1-7） */
    FinVoucher post(String voucherId);

    /** 凭证模板分页 */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.erp.entity.fin.FinVoucherTemplate> templatePage(
            long current, long size, String keyword);

    /** 模板保存（行 JSON 快照，金额不含） */
    com.erp.entity.fin.FinVoucherTemplate saveTemplate(com.erp.entity.fin.FinVoucherTemplate template);

    /** 模板删除（软删） */
    void deleteTemplate(String templateId);

    /** 模板一键调用 → 行草稿（停用科目行标注 blocked 不阻断，design D5） */
    Map<String, Object> applyTemplate(String templateId);

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
