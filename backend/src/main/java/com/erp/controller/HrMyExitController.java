package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrExit;
import com.erp.service.hr.HrExitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 员工自助端离职（/api/my/exit 认证即可，FR-4.7-4-11）：
 * 与 HR 代录共用同一 BIZ_EXIT 审批状态机，单据 SOURCE=SELF；
 * 通知期校验在提交时即时反馈（NOTICE_OK + 剩余天数）。
 */
@Tag(name = "我的离职")
@RestController
@RequestMapping("/api/my/exit")
@RequiredArgsConstructor
public class HrMyExitController {

    private final HrExitService exitService;
    private final HrEmployeeDao empDao;

    @PostMapping
    @Operation(summary = "提交离职申请（自助端，SOURCE=SELF）")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        payload.put("empId", myEmpId());
        payload.put("source", "SELF");
        HrExit x = exitService.apply(payload);
        // 即时反馈：通知期是否满足（L4 不阻断，FR-4.7-4-11）
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("exit", x);
        out.put("noticeOk", "1".equals(x.getNoticeOk()));
        out.put("noticeHint", "1".equals(x.getNoticeOk())
                ? "已满足法定提前通知期"
                : "未满足法定提前通知期（正式 30 天/试用 3 天），申请仍可提交并留痕");
        return R.ok(out);
    }

    @GetMapping
    @Operation(summary = "我的离职单")
    public R<List<HrExit>> myList() {
        return R.ok(exitService.listByEmp(myEmpId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "我的离职单详情（含交接清单进度）")
    public R<Map<String, Object>> myDetail(@PathVariable String id) {
        HrExit x = exitService.detail(id).get("exit") instanceof HrExit e ? e : null;
        if (x == null || !myEmpId().equals(x.getEmpId())) {
            throw new ServiceException(403, "非本人离职单");
        }
        return R.ok(exitService.detail(id));
    }

    /** 按当前登录账号反查员工 ID（自助端口径，同 HrMyPayrollController）。 */
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
