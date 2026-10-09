package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.qms.InspectionLotDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.entity.inv.InvDocType;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.sd.SdReturn;
import com.erp.service.inv.InboundWorkbenchService;
import com.erp.service.inv.StockDocTypeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入库作业台实现（spec inbound-workbench）。
 * 仅读来源域头表 + 检验批状态；过账/确认动作由前端直连既有 domain action（双入口同后端）。
 */
@Service
public class InboundWorkbenchServiceImpl implements InboundWorkbenchService {

    private final StockDocTypeService docTypeService;
    private final GoodsReceiptDao grDao;
    private final InspectionLotDao lotDao;
    private final SdReturnDao sdReturnDao;

    public InboundWorkbenchServiceImpl(StockDocTypeService docTypeService,
                                       GoodsReceiptDao grDao,
                                       InspectionLotDao lotDao,
                                       SdReturnDao sdReturnDao) {
        this.docTypeService = docTypeService;
        this.grDao = grDao;
        this.lotDao = lotDao;
        this.sdReturnDao = sdReturnDao;
    }

    @Override
    public Map<String, Object> tasks(String typeCode, String keyword, String status,
                                     long current, long size) {
        InvDocType type = docTypeService.getByCode(typeCode);
        if (type == null) {
            throw new ServiceException(422, "出入库业务类型不存在：" + typeCode);
        }
        Map<String, Object> typeMap = new LinkedHashMap<>();
        typeMap.put("code", type.getTypeCode());
        typeMap.put("name", type.getTypeName());
        typeMap.put("direction", type.getDirection());
        typeMap.put("enabled", type.getEnabled());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", typeMap);
        out.put("asOf", java.time.LocalDateTime.now());
        if (type.getEnabled() != null && type.getEnabled() == 0) {
            out.put("rows", List.of());
            out.put("total", 0);
            return out;   // 停用类型：enabled=false + 空列表（spec 场景）
        }

        switch (type.getTypeCode()) {
            case "PURCHASE_IN":
                return purchaseIn(typeMap, keyword, status, current, size, out);
            case "SALES_RETURN_IN":
                return returnIn(typeMap, keyword, status, current, size, out);
            default:
                // 未建域类型（WIP_IN/TRANSFER_IN/...）与暂无数据源类型：空态骨架
                out.put("rows", List.of());
                out.put("total", 0);
                return out;
        }
    }

    private Map<String, Object> purchaseIn(Map<String, Object> typeMap, String keyword,
                                           String status, long current, long size,
                                           Map<String, Object> out) {
        Page<GoodsReceipt> p = grDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<GoodsReceipt>()
                        .eq(!isBlank(status), GoodsReceipt::getStatus, status)
                        .and(!isBlank(keyword), w -> w
                                .like(GoodsReceipt::getGrNo, keyword)
                                .or().like(GoodsReceipt::getPoNo, keyword)
                                .or().like(GoodsReceipt::getBatchNo, keyword)
                                .or().like(GoodsReceipt::getSupplierName, keyword))
                        .orderByDesc(GoodsReceipt::getCreateDate));

        // 检验批状态联动（FR-4.4-1-3：按 GR 聚合其检验批状态，去重逗号串）
        Map<String, Set<String>> lotStatusByGr = new HashMap<>();
        List<String> grIds = p.getRecords().stream().map(GoodsReceipt::getId).toList();
        if (!grIds.isEmpty()) {
            List<InspectionLot> lots = lotDao.selectList(new LambdaQueryWrapper<InspectionLot>()
                    .in(InspectionLot::getGrId, grIds));
            for (InspectionLot lot : lots) {
                lotStatusByGr.computeIfAbsent(lot.getGrId(), k -> new LinkedHashSet<>())
                        .add(lot.getStatus());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (GoodsReceipt gr : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", gr.getId());
            row.put("docNo", gr.getGrNo());
            row.put("sourceDocNo", gr.getPoNo());
            row.put("supplierName", gr.getSupplierName());
            row.put("batchNo", gr.getBatchNo());
            row.put("sourceType", gr.getSourceType());
            row.put("status", gr.getStatus());
            row.put("lotStatus", String.join(",", lotStatusByGr.getOrDefault(
                    gr.getId(), new LinkedHashSet<>())));
            row.put("postingDocNo", gr.getPostingDocNo());
            row.put("confirmBy", gr.getConfirmBy());
            row.put("confirmAt", gr.getConfirmAt());
            row.put("arrivalDate", gr.getArrivalDate());
            row.put("createDate", gr.getCreateDate());
            rows.add(row);
        }
        out.put("type", typeMap);
        out.put("rows", rows);
        out.put("total", p.getTotal());
        return out;
    }

    private Map<String, Object> returnIn(Map<String, Object> typeMap, String keyword,
                                         String status, long current, long size,
                                         Map<String, Object> out) {
        Page<SdReturn> p = sdReturnDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SdReturn>()
                        .eq(!isBlank(status), SdReturn::getStatus, status)
                        .and(!isBlank(keyword), w -> w
                                .like(SdReturn::getReturnNo, keyword)
                                .or().like(SdReturn::getSoNo, keyword)
                                .or().like(SdReturn::getCustomerName, keyword))
                        .orderByDesc(SdReturn::getCreateDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SdReturn r : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("docNo", r.getReturnNo());
            row.put("sourceDocNo", r.getSoNo());
            row.put("customerName", r.getCustomerName());
            row.put("totalQty", r.getTotalQty());
            row.put("status", r.getStatus());
            row.put("createDate", r.getCreateDate());
            rows.add(row);
        }
        out.put("type", typeMap);
        out.put("rows", rows);
        out.put("total", p.getTotal());
        return out;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
