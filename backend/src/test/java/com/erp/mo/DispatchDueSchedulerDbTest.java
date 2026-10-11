package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.schedule.DispatchDueScheduler;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到期未派扫描库测（task 7.1，spec dispatch-management「到期未派提醒」，design D6）：
 * 今日应开工且有 PENDING 工序 → 推 MO_DISPATCH_DUE 计划域两方；
 * 同日重复扫描日节流不重推；全派不推；未到期不推。
 */
@SpringBootTest
class DispatchDueSchedulerDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private DispatchDueScheduler scheduler;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='MO_DISPATCH_DUE'");
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("due-base", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='MO_DISPATCH_DUE'");
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
    }

    /** Released 工单（planStartDate=planEndDate=startDay，库存齐套） */
    private MrpMo releasedMo(String suffix, LocalDate startDay) {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "到期扫描产品" + suffix, "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "到期扫描子件" + suffix, "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, 1);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        return MoSeed.releaseMo(moService, product, "10", startDay, startDay.plusDays(7));
    }

    private long noticeCount(String moId) {
        String key = moId + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                .format(TODAY);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MO_DISPATCH_DUE' AND BIZ_ID=?",
                Long.class, key);
        return n == null ? 0 : n;
    }

    @Test
    void duePushDayThrottleAndSkipCases() {
        // A：今日应开工 + PENDING 工序 → 推（计划域两方 = 2 行）
        MrpMo due = releasedMo("A", TODAY);
        // B：今日应开工但工序已全派 → 不推
        MrpMo dispatched = releasedMo("B", TODAY);
        moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                        .eq(MrpMoOp::getMoId, dispatched.getId()))
                .forEach(op -> {
                    op.setOpStatus("DISPATCHED");
                    moOpDao.updateById(op);
                });
        // C：计划开工日在未来 → 不推
        MrpMo future = releasedMo("C", TODAY.plusDays(30));

        // 第一轮：A 必命中（≥1——dev 库其他遗留 Released 单同被扫描，全局计数不隔离；
        // A/B/C 三单的定向断言在下方逐条验证）
        assertTrue(scheduler.doSweep() >= 1, "A 必命中推送（全局计数不隔离遗留单）");
        assertEquals(2, noticeCount(due.getId()), "A 计划域两方各 1 条");
        assertEquals(0, noticeCount(dispatched.getId()), "全派不推");
        assertEquals(0, noticeCount(future.getId()), "未到期不推");

        // 第二轮（同日）：日节流 → 0 推送、A 不重推（已读后也不重推——严格每日一轮）
        assertEquals(0, scheduler.doSweep(), "同日重复扫描日节流为 0");
        assertEquals(2, noticeCount(due.getId()), "重扫不追加");

        // A 全派后当日也不再推（节流优先——避免一天内反复打扰）
        moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                        .eq(MrpMoOp::getMoId, due.getId()))
                .forEach(op -> {
                    op.setOpStatus("DISPATCHED");
                    moOpDao.updateById(op);
                });
        assertEquals(0, scheduler.doSweep(), "全派后仍不推");

        // 次日键不同 → 自然放行（模拟：改 bizId 日期后重扫应推）
        jdbc.update("UPDATE erp_sys_notice SET BIZ_ID = REPLACE(BIZ_ID, ?, ?) "
                        + "WHERE BIZ_TYPE='MO_DISPATCH_DUE'",
                due.getId() + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                        .format(TODAY),
                due.getId() + "-20990101");
        assertEquals(0, scheduler.doSweep(), "A 已全派，次日也不会推（全派判定兜底）");
        // 造一张新的今日应派单验证次日键放行路径
        MrpMo due2 = releasedMo("D", TODAY);
        assertTrue(scheduler.doSweep() >= 1, "新到期单在无当日节流时推送（≥1 不隔离遗留）");
        assertTrue(noticeCount(due2.getId()) >= 2, "新单两方触达");
    }
}
