package com.erp.controller;

import com.erp.common.R;
import com.erp.security.PortalContext;
import com.erp.service.scm.CpfrExceptionService;
import com.erp.service.scm.CpfrFeedbackService;
import com.erp.service.scm.CpfrPlanService;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrForecastHeadDao;
import com.erp.dao.scm.CpfrForecastLineDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.entity.scm.CpfrForecastHead;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门户 CPFR 协同（spec cpfr-collaboration R8/R9）：
 * 预测包只读 / 反馈 / 例外调整意见 / 计划确认，全部经 requireSupplierId 行级隔离。
 */
@Tag(name = "门户-CPFR协同")
@RestController
@RequestMapping("/api/portal/cpfr")
public class PortalCpfrController {

    private final PortalContext portalContext;
    private final CpfrAgreementDao agreementDao;
    private final CpfrForecastHeadDao headDao;
    private final CpfrForecastLineDao lineDao;
    private final com.erp.dao.scm.CpfrPlanDao planDao;
    private final CpfrFeedbackService feedbackService;
    private final CpfrExceptionService exceptionService;
    private final CpfrPlanService planService;

    public PortalCpfrController(PortalContext portalContext,
                                CpfrAgreementDao agreementDao,
                                CpfrForecastHeadDao headDao,
                                CpfrForecastLineDao lineDao,
                                com.erp.dao.scm.CpfrPlanDao planDao,
                                CpfrFeedbackService feedbackService,
                                CpfrExceptionService exceptionService,
                                CpfrPlanService planService) {
        this.portalContext = portalContext;
        this.agreementDao = agreementDao;
        this.headDao = headDao;
        this.lineDao = lineDao;
        this.planDao = planDao;
        this.feedbackService = feedbackService;
        this.exceptionService = exceptionService;
        this.planService = planService;
    }

    @Operation(summary = "我的 CPFR 协议")
    @GetMapping("/agreements")
    public R<List<Map<String, Object>>> agreements() {
        String supplierId = portalContext.requireSupplierId();
        var list = agreementDao.selectList(new LambdaQueryWrapper<com.erp.entity.scm.CpfrAgreement>()
                .eq(com.erp.entity.scm.CpfrAgreement::getSupplierId, supplierId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var a : list) {
            out.add(com.erp.service.impl.scm.CpfrAgreementServiceImpl.toMap(a));
        }
        return R.ok(out);
    }

    @Operation(summary = "预测数据包（只读含快照，仅本供应商协议）")
    @GetMapping("/forecast-packages")
    public R<List<Map<String, Object>>> forecastPackages(@RequestParam(required = false) String periodTag) {
        String supplierId = portalContext.requireSupplierId();
        List<String> agrIds = ownAgreementIds(supplierId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String agrId : agrIds) {
            var lines = lineDao.selectList(new LambdaQueryWrapper<com.erp.entity.scm.CpfrForecastLine>()
                    .eq(com.erp.entity.scm.CpfrForecastLine::getAgreementId, agrId));
            for (var l : lines) {
                var head = headDao.selectById(l.getHeadId());
                if (head == null || (periodTag != null && !periodTag.equals(head.getPeriodTag()))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("periodTag", head.getPeriodTag());
                m.put("itemCode", l.getItemCode());
                m.put("itemName", l.getItemName());
                m.put("weekOffset", l.getWeekOffset());
                m.put("forecastQty", l.getForecastQty());
                m.put("snapshotStock", l.getSnapshotStock());
                m.put("snapshotOnhandPo", l.getSnapshotOnhandPo());
                m.put("safetyStock", l.getSafetyStock());
                out.add(m);
            }
        }
        return R.ok(out);
    }

    @Operation(summary = "我的待反馈/反馈历史")
    @GetMapping("/feedbacks")
    public R<List<Map<String, Object>>> feedbacks(@RequestParam(required = false) String periodTag) {
        String supplierId = portalContext.requireSupplierId();
        List<String> agrIds = ownAgreementIds(supplierId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String agrId : agrIds) {
            out.addAll(feedbackService.monitor(agrId, periodTag));
        }
        return R.ok(out);
    }

    @Operation(summary = "提交反馈（可供量/产能约束/风险提示）")
    @PostMapping("/feedbacks")
    public R<Map<String, Object>> submitFeedback(@RequestBody Map<String, Object> payload) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(feedbackService.submit(supplierId, payload));
    }

    @Operation(summary = "我的待处理例外项")
    @GetMapping("/exceptions")
    public R<List<Map<String, Object>>> exceptions() {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(exceptionService.portalList(supplierId));
    }

    @Operation(summary = "例外调整意见（数量/说明）")
    @PostMapping("/exceptions/{id}/opinion")
    public R<Map<String, Object>> opinion(@PathVariable String id,
                                          @RequestBody Map<String, Object> payload) {
        String supplierId = portalContext.requireSupplierId();
        payload.put("exceptionId", id);
        return R.ok(exceptionService.supplierOpinion(supplierId, payload));
    }

    @Operation(summary = "我的联合补货计划")
    @GetMapping("/plans")
    public R<List<Map<String, Object>>> plans(@RequestParam(required = false) String periodTag) {
        String supplierId = portalContext.requireSupplierId();
        List<String> agrIds = ownAgreementIds(supplierId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String agrId : agrIds) {
            out.addAll(planService.list(agrId, periodTag));
        }
        return R.ok(out);
    }

    @Operation(summary = "确认联合补货计划（供应商方，留痕）")
    @PostMapping("/plans/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        String supplierId = portalContext.requireSupplierId();
        var plan = planDaoSelect(id);
        if (plan == null || !agrIds(supplierId).contains(plan.getAgreementId())) {
            throw new com.erp.common.ServiceException(404, "联合补货计划不存在"); // 行级隔离
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return R.ok(planService.confirm(id, "SUPPLIER", auth == null ? null : auth.getName()));
    }

    private com.erp.entity.scm.CpfrPlan planDaoSelect(String id) {
        return planDao.selectById(id);
    }

    private List<String> ownAgreementIds(String supplierId) {
        return agrIds(supplierId);
    }

    private List<String> agrIds(String supplierId) {
        return agreementDao.selectList(new LambdaQueryWrapper<com.erp.entity.scm.CpfrAgreement>()
                        .eq(com.erp.entity.scm.CpfrAgreement::getSupplierId, supplierId))
                .stream().map(com.erp.entity.scm.CpfrAgreement::getId).toList();
    }
}
