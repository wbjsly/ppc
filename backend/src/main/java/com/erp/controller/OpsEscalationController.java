package com.erp.controller;

import com.erp.common.R;
import com.erp.dao.ops.OpsOncallDao;
import com.erp.entity.ops.OpsOncall;
import com.erp.service.ops.EscalationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 18.3 升级管理：值班看板/层级升级/升级工单。
 */
@Tag(name = "运维升级管理")
@RestController
@RequestMapping("/api/ops/escalations")
public class OpsEscalationController {

    private final EscalationService service;
    private final OpsOncallDao oncallDao;
    private final JdbcTemplate jdbc;

    public OpsEscalationController(EscalationService service, OpsOncallDao oncallDao, JdbcTemplate jdbc) {
        this.service = service;
        this.oncallDao = oncallDao;
        this.jdbc = jdbc;
    }

    @Operation(summary = "值班看板（18.3.1，近 7 天排班）")
    @GetMapping("/oncall")
    public R<List<Map<String, Object>>> oncall(@RequestParam(defaultValue = "7") int days) {
        return R.ok(jdbc.queryForList(
                "SELECT ID AS id, DUTY_DATE AS dutyDate, ON_LEVEL AS onLevel, USER_NAME AS userName, "
                        + "BACKUP_USER AS backupUser FROM erp_ops_oncall WHERE DEL_FLAG='0' "
                        + "AND DUTY_DATE >= CURDATE() ORDER BY DUTY_DATE, ON_LEVEL LIMIT ?",
                Math.min(Math.max(days, 1), 30) * 3));
    }

    @Operation(summary = "排班维护")
    @PostMapping("/oncall/save")
    public R<Map<String, Object>> saveOncall(@RequestParam String dutyDate,
                                             @RequestParam String level,
                                             @RequestParam String userName,
                                             @RequestParam(required = false) String backupUser) {
        OpsOncall o = new OpsOncall();
        o.setDutyDate(LocalDate.parse(dutyDate));
        o.setOnLevel(level);
        o.setUserName(userName);
        o.setBackupUser(backupUser);
        o.setCreateBy("ops");
        // 同日同层级唯一：存在则更新
        List<OpsOncall> exists = oncallDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpsOncall>()
                        .eq(OpsOncall::getDutyDate, o.getDutyDate())
                        .eq(OpsOncall::getOnLevel, o.getOnLevel()));
        if (!exists.isEmpty()) {
            o.setId(exists.get(0).getId());
            oncallDao.updateById(o);
        } else {
            oncallDao.insert(o);
        }
        return R.ok(Map.of("id", o.getId()));
    }

    @Operation(summary = "层级升级日志（18.3.2）")
    @GetMapping("/logs")
    public R<List<Map<String, Object>>> logs(
            @RequestParam(required = false) String alertId,
            @RequestParam(defaultValue = "50") int limit) {
        return R.ok(service.escalationLogs(alertId, limit));
    }

    @Operation(summary = "手动触发一轮升级扫描（测试/运维用）")
    @PostMapping("/scan")
    public R<Map<String, Object>> scan() {
        return R.ok(Map.of("handled", service.scanAndEscalate()));
    }

    @Operation(summary = "升级工单视图（18.3.3，已升级告警）")
    @GetMapping("/tickets")
    public R<List<Map<String, Object>>> tickets() {
        return R.ok(jdbc.queryForList(
                "SELECT ID AS id, ALERT_NO AS alertNo, LEVEL AS level, TITLE AS title, "
                        + "STATUS AS status, ESCALATION_LEVEL AS escalationLevel, HANDLER AS handler, "
                        + "CREATE_DATE AS createDate FROM erp_ops_alert WHERE DEL_FLAG='0' "
                        + "AND ESCALATION_LEVEL > 0 ORDER BY CREATE_DATE DESC LIMIT 100"));
    }
}
