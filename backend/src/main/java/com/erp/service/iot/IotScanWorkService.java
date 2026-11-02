package com.erp.service.iot;

import java.util.Map;

/**
 * 扫码作业五场景薄适配（13.2，spec iot-mobile：FR-4.11-2-x）。
 * 复用 PROC/INV/SD 既有服务层校验，不改其本体；本层补移动端能力：
 * RFID 序列号防重与损坏拒收（C-4.11-02）、批次不一致冻结货位、
 * 移动端异常码映射（L1/L4/冻结/复盘/盘亏 L2 提示语）。
 */
public interface IotScanWorkService {

    /**
     * 13.2.1 收货扫码：序列号校验（缺失/损坏 → L1 409；重复 → L1 409 防重）
     * 后转收货登记（复用 GoodsReceiptService）。
     *
     * @return {grNo, serialNo, blocked=false} 或抛 ServiceException
     */
    Map<String, Object> receive(Map<String, Object> payload);

    /**
     * 13.2.2 上架扫码：货位/存储条件校验（不匹配 L1 409；已满自动推荐备选）
     * 后转货位分配（复用 BinAssignmentService）。
     */
    Map<String, Object> putaway(Map<String, Object> payload);

    /**
     * 13.2.3 拣货扫码：三码校验（复用 PickScanService.verify）；
     * 实物批次与系统批次不一致 → L1 409 + 冻结该货位库存（本层补）+ 通知仓库主管。
     */
    Map<String, Object> pickScan(Map<String, Object> payload);

    /**
     * 13.2.4 发货扫码：逐件复核（物料/批次/数量比发货清单；不一致 L1 停止发货）
     * 后转发货确认（复用 ShipmentService）。
     */
    Map<String, Object> shipReview(Map<String, Object> payload);

    /**
     * 13.2.5 盘点扫码：实盘录入（复用 CountInputService.submitCount）；
     * 大额盘亏（>10% 阻断待复盘；超容差审批）→ L2 强制审批提示语映射。
     */
    Map<String, Object> countScan(Map<String, Object> payload);
}
