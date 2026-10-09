package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.vmi.VmiAgreementLine;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VMI 寄售协议（FR-4.2-8-1，spec vmi-consignment）。
 * 协议 CRUD/状态流转 + 寄售 PO 判定（FR-4.2-8-1 SOP）+ 下达卡控（BR-4.2-35）
 * + 领用计价/水位行查询（C-4.2-09 / BR-4.2-36）。
 */
public interface VmiAgreementService {

    /** 创建协议（头 + 物料清单行，状态 DRAFT），编号 VM+yyyyMM+流水 */
    Map<String, Object> create(Map<String, Object> payload);

    /** 更新协议头与行（仅 DRAFT 可改；行整组替换） */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 详情：{ agreement, lines } */
    Map<String, Object> detail(String id);

    /** 分页（供应商 / 状态筛选），行附 lineCount */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String status);

    /** DRAFT → EFFECTIVE（须有行且失效日不早于今天） */
    void effective(String id);

    /** 置为停用 DISABLED */
    void disable(String id);

    /**
     * 命中判定（PO 创建自动判定，design D9）：
     * 返回该供应商「生效中且今日在有效期内」协议清单内包含的物料子集。
     */
    Set<String> hitItems(String supplierId, Collection<String> itemCodes);

    /**
     * 生效协议内该物料的清单行（取价/取水位用；同供应商最新协议优先）。
     * 仅按协议状态 EFFECTIVE 过滤，不看日期窗口（水位校验与创建取价共用）。
     * 无命中返回 null。
     */
    VmiAgreementLine lineFor(String supplierId, String itemCode);

    /**
     * 领用计价行（C-4.2-09）：协议生效 + 领用日在协议有效期内 + 价格条款覆盖领用日。
     * 无命中返回 null（调用方负责 422 提示）。
     */
    VmiAgreementLine priceLineOn(String supplierId, String itemCode, LocalDate onDate);

    /**
     * 寄售 PO 下达卡控（BR-4.2-35，L1）：
     * 该供应商须存在生效中且失效日期 ≥ PO 交期的 VMI 协议，否则 422 提示续签。
     * （供应商状态合格校验由调用方复用 BR-4.2-18 逻辑。）
     */
    void requireIssueable(String supplierId, LocalDate deliveryDate);
}
