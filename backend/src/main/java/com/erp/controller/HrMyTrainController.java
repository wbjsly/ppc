package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.service.hr.HrTrainPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 员工自助端培训任务（/api/my/train-tasks）：我的任务与确认参加/已完成。 */
@Tag(name = "我的培训")
@RestController
@RequestMapping("/api/my/train-tasks")
@RequiredArgsConstructor
public class HrMyTrainController {

    private final HrTrainPlanService planService;
    private final HrEmployeeDao empDao;

    @GetMapping
    @Operation(summary = "我的培训任务（含计划信息）")
    public R<List<Map<String, Object>>> myList() {
        return R.ok(planService.myTasks(myEmpId()));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认参加/已完成（完成判定仍以 HR 登记为准）")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(planService.confirmTask(id, myEmpId()));
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
