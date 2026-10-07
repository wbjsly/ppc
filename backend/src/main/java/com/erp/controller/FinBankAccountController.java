package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.BankAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 银行账户（2.7.3，spec bank-account）：只读查询，余额在付款执行时条件扣减。 */
@Tag(name = "银行账户")
@RestController
@RequestMapping("/api/fin/bank-accounts")
public class FinBankAccountController {

    private final BankAccountService service;

    public FinBankAccountController(BankAccountService service) {
        this.service = service;
    }

    @Operation(summary = "账户列表（含余额）")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    @Operation(summary = "账户详情")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }
}
