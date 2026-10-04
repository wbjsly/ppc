package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.mdm.MdmTaxWorkbenchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 政策驱动税码批量变更工作台（1.6.2 增强，S-4.1-07 闭环）。
 * 路径挂在 /api/mdm/tax-policies/{id}/workbench 下 → SecurityConfig 既有
 * POST /api/mdm/tax-policies/** ADMIN 规则覆盖（design D6）。
 */
@RestController
@RequestMapping("/api/mdm/tax-policies/{id}/workbench")
public class MdmTaxWorkbenchController {

    private final MdmTaxWorkbenchService service;

    public MdmTaxWorkbenchController(MdmTaxWorkbenchService service) {
        this.service = service;
    }

    /** 候选税码（链尾/计算态/建议切换日） */
    @GetMapping("/candidates")
    public R<Map<String, Object>> candidates(@PathVariable String id,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(service.candidates(id, keyword));
    }

    /** 预检（dry-run 不落库）：五类计划逐行返回 */
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        return R.ok(service.preview(id, rows(body),
                str(body.get("switchDate")), str(body.get("expireDate"))));
    }

    /** 行级独立提交（部分失败不回滚，PARTIAL 可重试降级） */
    @PostMapping("/submit")
    public R<Map<String, Object>> submit(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(service.submit(id, rows(body),
                str(body.get("switchDate")), str(body.get("expireDate"))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> body) {
        Object rows = body.get("rows");
        if (!(rows instanceof List)) {
            throw new ServiceException(422, "rows 必填");
        }
        return (List<Map<String, Object>>) rows;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
