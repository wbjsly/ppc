package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.InputTaxTransferOutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 进项税额转出接口（M9-4-3，spec invoice-management ADDED 进项税额转出）。
 */
@RestController
@RequestMapping("/api/fin/input-tax-transfers")
@RequiredArgsConstructor
@Tag(name = "进项税额转出（M9-4-3）")
public class InputTaxTransferOutController {

    private final InputTaxTransferOutService service;

    @PostMapping("/direct")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "直接转出（选 CERTIFIED 票 + 金额，累计不超已抵扣）")
    public R<Map<String, Object>> direct(@RequestBody Map<String, Object> payload) {
        return R.ok(service.directTransfer(payload));
    }

    @PostMapping("/apportion")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "分摊转出（BR-4.14-22 公式：无法划分进项税额 × 免税简易项目销售额占比）")
    public R<Map<String, Object>> apportion(@RequestBody Map<String, Object> payload) {
        return R.ok(service.apportionTransfer(payload));
    }
}
