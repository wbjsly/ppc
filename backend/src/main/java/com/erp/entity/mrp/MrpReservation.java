package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 物料预扣行（change add-dispatch-management，spec dispatch-management「物料预扣」，迁移 122）。
 * design D2：派工确认按工单 BOM 聚合预扣，ON 开关实扣 AVAILABLE_QTY（仿冻结先例）、
 * OFF 仅记账；核销余量 REMAIN_QTY 为账目护栏（领料过账 min(行量, 余量) 递减）。
 * 同工单同物料至多一条活动行：UNIQUE(MO_ID, ITEM_CODE, ACTIVE_FLAG)，活动='1' 关闭=NULL。
 * TAKES_JSON 记录逐库存行的实扣明细 [{"id":"stockId","qty":"10"}]——核销/释放回补
 * 按 take 逐行归位（锁与出库解耦：回补目标 = 锁所在行，行余量恒 ≥ take 剩余，无需 cap）。
 * 无 TAKES 的行（OFF 记账或未实扣）核销/释放只减余量、不动库存。
 */
@Getter
@Setter
@TableName("erp_mrp_reservation")
public class MrpReservation extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_CLEARED = "CLEARED";
    public static final String ST_RELEASED = "RELEASED";

    private String moId;
    /** 工单号（领料单 WORK_ORDER_NO 核销匹配键） */
    private String moNo;
    private String itemCode;
    private String itemName;
    /** 需求数量（BOM 快照行按剩余量基数聚合，与 5.5 齐套口径一致） */
    private BigDecimal reqQty;
    /** 实扣数量 = min(可用, 需求)；OFF 开关时为账面值、库存未动 */
    private BigDecimal reservedQty;
    /** 核销余量（账目护栏，随领料递减） */
    private BigDecimal remainQty;
    /** ACTIVE/CLEARED/RELEASED */
    private String status;
    /** 活动='1' 关闭=NULL（唯一索引多 NULL 技巧） */
    private String activeFlag;
    /** 取消/关闭/驳回释放原因 */
    private String releaseReason;
    /** 逐库存行实扣明细 JSON：[{"id":"...","qty":"..."}] */
    private String takesJson;
}
