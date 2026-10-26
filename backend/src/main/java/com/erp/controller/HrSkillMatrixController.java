package com.erp.controller;

import com.erp.common.R;
import com.erp.service.hr.HrSkillMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 技能矩阵查询（skill-matrix，按技能查有效人员，供生产派工等下游复用）。 */
@Tag(name = "技能矩阵")
@RestController
@RequestMapping("/api/hr/skill-matrix")
@RequiredArgsConstructor
public class HrSkillMatrixController {

    private final HrSkillMatrixService matrixService;

    @GetMapping("/by-skill")
    @Operation(summary = "按技能查有效（ACTIVE）且在职人员（level 精确过滤可空）")
    public R<List<Map<String, Object>>> bySkill(@RequestParam String skillCode,
                                                @RequestParam(required = false) String level) {
        return R.ok(matrixService.queryBySkill(skillCode, level));
    }
}
