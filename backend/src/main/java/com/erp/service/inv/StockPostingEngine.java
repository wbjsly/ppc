package com.erp.service.inv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 通用出入库过账引擎（spec stock-posting-engine，design D2 参数化契约）。
 * 单事务五步：校验链 → OUT 批次分配 → 库存数量变更 → 出入库流水 → 快照/事件（域服务同事务内调用）。
 * 引擎是 erp_inv_stock 数量变更的唯一通道（冻结/解冻平移除外）与唯一库存行锁持有者（design D4 锁序）。
 * 域单据级逻辑（PO 回写/凭证/暂估/检验前置/预留消耗/寄售）留在域服务。
 */
public interface StockPostingEngine {

    /**
     * 过账入口（ REQUIRED 传播，加入调用方事务）。
     * 422：类型不存在/停用、量非正、负库存（库存不足，当前可用量为 X）、序列重复/不可用、批次效期缺失；
     * 409：并发冲突（唯一键/行锁影响行数 0）。
     */
    Result post(Request request);

    /**
     * 缺省批次分配 dry-run（创建期配批预检用，无锁提示性视图；design D3 排序：
     * INBOUND_DATE → EXPIRY_DATE → CREATE_DATE 升序，分配可用 = AVAILABLE − 该维度 ACTIVE 预留）。
     */
    List<Alloc> allocate(String warehouseCode, String itemCode, BigDecimal qty);

    /** 过账请求 */
    class Request {
        /** 类型码（erp_inv_doc_type，须 ENABLED=1） */
        public String typeCode;
        /** 来源单据类型 GR/SHIPMENT/RETURN/MI... */
        public String bizDocType;
        public String bizDocNo;
        public String remark;
        public List<Line> lines;

        public static Request of(String typeCode, String bizDocType, String bizDocNo, List<Line> lines) {
            Request r = new Request();
            r.typeCode = typeCode;
            r.bizDocType = bizDocType;
            r.bizDocNo = bizDocNo;
            r.lines = lines;
            return r;
        }
    }

    /** 单行（IN 携带批次/仓位/效期/序列；OUT 可缺批次由引擎分配） */
    class Line {
        public String warehouseCode;
        public String itemCode;
        public String itemName;
        /** 无批次物料为空串/null（OUT 缺省=引擎分配） */
        public String batchNo;
        /** IN：目标仓位（空/null=未分配虚拟位 ''；OUT 忽略，由位行分配产生） */
        public String binCode;
        public BigDecimal qty;
        /** IN：true 入让步锁定列 QC_QTY（否则 AVAILABLE_QTY） */
        public boolean intoQc;
        /** OUT：true 先核销 QC_QTY 再扣 AVAILABLE（退货链 qcFirst） */
        public boolean qcFirst;
        /** 序列号清单（判重/可用性校验） */
        public List<String> serials;
        /** IN 建档携带 */
        public String supplierBatchNo;
        public LocalDate expiryDate;
        /** OUT 指定批次标记（batchNo 非空即视为指定；留字段语义化） */
        public boolean batchSpecified;
    }

    /** 分配结果（lineIndex 对应回请求行，拆批/跨位拆行产生多条） */
    class Alloc {
        public int lineIndex;
        public String batchNo;
        /** 分配到的仓位（空串=未分配位；change add-bin-assignment） */
        public String binCode;
        public BigDecimal qty;
        public LocalDate inboundDate;
        public LocalDate expiryDate;
        /** 该批次库存行物料名（配批回填行用） */
        public String itemName;

        public static Alloc of(int lineIndex, String batchNo, BigDecimal qty) {
            Alloc a = new Alloc();
            a.lineIndex = lineIndex;
            a.batchNo = batchNo;
            a.qty = qty;
            return a;
        }
    }

    /** 过账结果 */
    class Result {
        /** 实际分配/扣减明细（回写域单据 BATCH_ALLOC） */
        public List<Alloc> allocations;
        /** 本单生成的流水号 */
        public List<String> txnNos;
    }
}
