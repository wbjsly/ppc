package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.mdm.MdmTaxCodeVersion;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 税码维护业务能力契约（税码管理 1.6.1，FR-4.1-3-2 + BR-4.1-16/17/18）。
 */
public interface MdmTaxCodeService {

    /**
     * 分页：keyword（税码/政策文号）/scope/calcType/lifecycle（计算态）筛选；
     * 每行附 lifecycle（NOT_EFFECTIVE/EFFECTIVE/EXPIRED）计算态，不落库。
     */
    Page<Map<String, Object>> page(long current, long size, String keyword, String scope,
                                   String calcType, String lifecycle);

    MdmTaxCode getById(String id);

    /**
     * 创建：字段校验（TaxCodeRules，BR-4.1-16/C-4.1-04）、税率 4 位精度、
     * 区间冲突/断档（BR-4.1-17，税码序列）；CREATE 快照 + TAXCODE.CREATED 事件（D3 键含 ID）。
     */
    MdmTaxCode create(MdmTaxCode tax);

    /**
     * 变更：税码编号锁定（C-4.1-01）、历史（失效日<今天）422（BR-4.1-18）、
     * 原因必填、重跑区间校验、UPDATE 快照 + TAXCODE.UPDATED 事件。
     */
    MdmTaxCode update(MdmTaxCode tax);

    /** 删除：历史 422；生效中/未生效软删 */
    void delete(String id);

    /**
     * 按预检计划创建（工作台专用，add-tax-policy-workbench D4）：与 create 同责（字段校验/精度/
     * 快照/事件），但跳过区间复验——链内中间插入（补洞/缩短后新建）会被 create 的「贴全局链首尾」
     * 语义误判，计划有效性已由工作台 checkAdjacent 预检保证。
     */
    MdmTaxCode createSegment(MdmTaxCode tax);

    /**
     * 缩短区间（工作台专用，add-tax-policy-workbench D4）：仅校验 非历史/新失效日∈[生效日,原失效日)/乐观锁，
     * 不跑区间复验（整计划由工作台 IntervalRules 预检保证）；快照 UPDATE + TAXCODE.UPDATED 事件。
     */
    MdmTaxCode shortenSegment(String id, java.time.LocalDate newExpireDate, String reason);

    /**
     * 按日期试算（4.14 预铺，C-4.1-01 口径）：命中返回当时生效记录；
     * 无命中 → 明示「该日期无有效税码」+ 非空 reasons。
     */
    Map<String, Object> trial(String taxCode, LocalDate date);

    List<MdmTaxCodeVersion> versions(String entityId);

    Map<String, Object> diff(String entityId, int from, int to);
}
