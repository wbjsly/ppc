package com.erp.service.inv;

import com.erp.entity.inv.InvSerial;

import java.util.List;
import java.util.Map;

/**
 * 序列号台账（4.2.2，spec serial-master）能力契约：
 * 序列号手工录入、全局唯一（防串码）、创建后不可改；状态机流转经服务层白名单强制并留痕；
 * 判重查询为写入点预留（BR-4.11-15 口径，仅需认证，只读）。
 */
public interface SerialService {

    /** 查询（序列号/物料/批次/状态筛选，创建时间倒序） */
    List<InvSerial> query(String serialNo, String itemCode, String batchNo, String status);

    /** 单个序列 */
    InvSerial get(String id);

    /** 新建（状态初始 IN_STOCK；全局唯一 409；序列号锁定 422） */
    InvSerial create(InvSerial serial);

    /** 变更备注类字段（序列号/物料锁定 422；状态只经 transition 变更） */
    InvSerial update(InvSerial serial);

    /**
     * 状态流转（D3 单点强制）：to ∈ {OUT, FROZEN, SCRAPPED, IN_STOCK}，合法边白名单校验；
     * 解冻（FROZEN→IN_STOCK）原因必填；写流转留痕；乐观锁并发 409。
     */
    InvSerial transition(String serialId, String toStatus, String reason, String locationRemark);

    /** 流转记录（按时间倒序） */
    List<Map<String, Object>> logs(String serialId);

    /** 判重查询（D4：仅需认证，只读）：exists/status/batchNo/locationRemark */
    Map<String, Object> check(String itemCode, String serialNo);
}
