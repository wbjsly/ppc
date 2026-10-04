package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.ProcMrpService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 模拟 MRP 净算（2.1.1，/api/proc/mrp）：preview dry-run / generate 生成 PR。 */
@RestController
@RequestMapping("/api/proc/mrp")
public class ProcMrpController {

    private final ProcMrpService service;

    public ProcMrpController(ProcMrpService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        return R.ok(service.preview(rows(body)));
    }

    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        return R.ok(service.generate(rows(body)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> body) {
        Object rows = body.get("rows");
        if (!(rows instanceof List)) {
            throw new ServiceException(422, "rows 必填");
        }
        return (List<Map<String, Object>>) rows;
    }
}
