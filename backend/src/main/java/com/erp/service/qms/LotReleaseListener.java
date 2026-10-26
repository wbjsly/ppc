package com.erp.service.qms;

import com.erp.entity.qms.InspectionLot;

/**
 * 检验批放行监听（change add-rework-management，spec rework-execution
 * 「复检合格入库解冻关单」FR-4.5-7-8——复检合格放行回调同事务执行返工闭环）。
 * confirmRelease 成功后同步回调；实现方自行按 refId/类型过滤（非返工批直接忽略）。
 */
public interface LotReleaseListener {

    /** 检验批确认放行（status=RELEASED, result=PASS 之后，同事务） */
    void onReleased(InspectionLot lot);
}
