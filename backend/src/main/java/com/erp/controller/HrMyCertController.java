package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrEmployeeCert;
import com.erp.service.hr.HrCertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 员工自助端证书（/api/my/certs 认证即可）：提交与我的证书列表（含到期倒计时）。
 * 扫描件经 /api/common/attachments（BIZ_TYPE=TRAIN_CERT，BIZ_ID=证书ID）上传。
 */
@Tag(name = "我的证书")
@RestController
@RequestMapping("/api/my/certs")
@RequiredArgsConstructor
public class HrMyCertController {

    private final HrCertService certService;
    private final HrEmployeeDao empDao;

    @GetMapping
    @Operation(summary = "我的证书（含类别、附件、到期倒计时与状态）")
    public R<List<Map<String, Object>>> myList() {
        return R.ok(certService.myList(myEmpId()));
    }

    @PostMapping
    @Operation(summary = "提交证书（PENDING 待 HR 审核；同类别有效证书未归档时拒绝）")
    public R<HrEmployeeCert> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(certService.submit(myEmpId(), payload));
    }

    /** 按当前登录账号反查员工 ID（同 HrMyExitController 口径）。 */
    private String myEmpId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        HrEmployee e = empDao.selectOne(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getUserId, username)
                .eq(HrEmployee::getDelFlag, "0"));
        if (e == null) {
            throw new ServiceException(404, "当前账号未关联员工档案");
        }
        return e.getId();
    }
}
