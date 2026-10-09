package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.mdm.MdmExchangeRateBatchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 汇率批量更新（汇率管理 1.5.2，/api/mdm/exchange-rate-batch） */
@RestController
@RequestMapping("/api/mdm/exchange-rate-batch")
public class MdmExchangeRateBatchController {

    private final MdmExchangeRateBatchService service;

    public MdmExchangeRateBatchController(MdmExchangeRateBatchService service) {
        this.service = service;
    }

    /** 行级预检（dry-run 不落库） */
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        return R.ok(service.preview(parseRows(body), str(body.get("defaultSourceFileNo"))));
    }

    /** 按行独立提交（部分失败不回滚成功行） */
    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@RequestBody Map<String, Object> body) {
        return R.ok(service.batch(parseRows(body), str(body.get("defaultSourceFileNo"))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRows(Map<String, Object> body) {
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
