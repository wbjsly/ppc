package com.erp.service.scm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.scm.ScmScorecardAppeal;
import com.erp.entity.scm.ScmScorecardModel;
import com.erp.entity.scm.ScmScorecardRectify;
import com.erp.entity.scm.ScmScorecardResult;

import java.util.List;
import java.util.Map;

/** 2.9.2 供应商绩效记分卡（spec supplier-scorecard，4.9 流程七全链）。 */
public interface ScorecardService {

    // ---- 模型（6.1） ----
    Map<String, Object> saveModel(Map<String, Object> payload);
    Page<ScmScorecardModel> modelPage(long current, long size, String status);

    // ---- 采集与计算（6.2/6.3）：自动抽取不可手工修改，无 UPDATE 入口 ----
    Map<String, Object> collectMonth(String monthTag);

    // ---- 查询 ----
    Page<ScmScorecardResult> resultPage(long current, long size, String monthTag, String grade,
                                         String status, String supplierId);
    Map<String, Object> detail(String resultId);

    // ---- 审核公示（6.5） ----
    Map<String, Object> review(String resultId, String opinion);
    Map<String, Object> publish(String resultId);

    // ---- 申诉（6.6） ----
    Map<String, Object> appeal(Map<String, Object> payload);
    Map<String, Object> reviewAppeal(String appealId, boolean confirmed, String conclusion);

    // ---- 整改与冻结（6.4） ----
    Page<ScmScorecardRectify> rectifyPage(long current, long size, String status);
    Map<String, Object> closeRectify(String rectifyId, String note);
    Map<String, Object> unfreeze(String rectifyId);
    /** PO 创建前冻结校验（BR-4.9-05：冻结即 422 阻断新单，在途不受影响） */
    void assertNotFrozen(String supplierId);

    // ---- 门户（C-4.9-06 仅见自身） ----
    List<ScmScorecardResult> portalList(String supplierId);
    Map<String, Object> portalDetail(String resultId, String supplierId);
}
