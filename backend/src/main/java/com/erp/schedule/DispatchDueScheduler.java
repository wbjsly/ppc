package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.system.SysNoticeDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.system.SysNotice;
import com.erp.service.SysParamService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 到期未派扫描（change add-dispatch-management design D6，spec dispatch-management
 * 「到期未派提醒」）：扫「计划开工日 ≤ 今日 且仍有 PENDING 工序」的 Released 工单，
 * 推 MO_DISPATCH_DUE 给计划域（PLANNER/PLAN_MGR）。
 *
 * 通知纪律（D6 silent 沿用）：这是典型「人不在场」事件（该派没派），推铃铛有真实信息增量；
 * 扫描纯读业务数据——唯一写入是通知表（通知域，非业务数据）。
 *
 * 日节流：bizId = MO_ID-yyyyMMdd，推送前查「今日该键是否已存在记录（不看已读）」——
 * 已读后再扫不重推（严格每日最多一轮/工单）；次日键不同自然放行。
 * push 自带幂等（同 bizType+bizId+接收方未读去重）作为第二道防线。
 *
 * 并发：单实例 Docker + @Scheduled 单线程 + synchronized（同 KittingSweepScheduler 先例）。
 */
@Slf4j
@Component
public class DispatchDueScheduler {

    public static final String BIZ_DUE = "MO_DISPATCH_DUE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 接收方：计划域两方（spec「通知计划员」；计划主管为审批相关方） */
    private static final String[] ROLES = {"ROLE_PLANNER", "ROLE_PLAN_MGR"};

    private final MrpMoDao moDao;
    private final MrpMoOpDao moOpDao;
    private final SysNoticeDao noticeDao;
    private final NoticeService noticeService;
    private final SysParamService sysParam;

    public DispatchDueScheduler(MrpMoDao moDao, MrpMoOpDao moOpDao, SysNoticeDao noticeDao,
                                NoticeService noticeService, SysParamService sysParam) {
        this.moDao = moDao;
        this.moOpDao = moOpDao;
        this.noticeDao = noticeDao;
        this.noticeService = noticeService;
        this.sysParam = sysParam;
    }

    /**
     * 默认 5 分钟（yml app.dispatch-due.sweep-ms 可覆盖节奏）；
     * sys_param DISPATCH_DUE_SWEEP_MS ≤ 0 = 停用（迁移 122 登记，默认 300000 启用）。
     */
    @Scheduled(fixedDelayString = "${app.dispatch-due.sweep-ms:300000}")
    public void sweep() {
        try {
            int ms = sysParam.getInt("DISPATCH_DUE_SWEEP_MS", 300000);
            if (ms <= 0) {
                return; // 参数停用
            }
            doSweep();
        } catch (Exception e) {
            log.warn("到期未派扫描失败（下轮重试）：{}", e.getMessage());
        }
    }

    /**
     * 执行扫描（测试可直接调用；synchronized 防重入）。
     *
     * @return 本轮实际推送的工单数
     */
    public synchronized int doSweep() {
        LocalDate today = LocalDate.now();
        String dayKey = today.format(DAY);
        // Released + 计划开工日已到（含今日）——过期未派同样是「该派没派」
        List<MrpMo> mos = moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .eq(MrpMo::getStatus, MrpMo.ST_RELEASED)
                .le(MrpMo::getPlanStartDate, today)
                .orderByAsc(MrpMo::getPlanStartDate));
        int pushed = 0;
        for (MrpMo mo : mos) {
            // 仍有未派工序才算「未派完」；全派 → 不推（任务口径）
            Long pending = moOpDao.selectCount(new LambdaQueryWrapper<MrpMoOp>()
                    .eq(MrpMoOp::getMoId, mo.getId())
                    .eq(MrpMoOp::getOpStatus, "PENDING"));
            if (pending == null || pending == 0) {
                continue;
            }
            String bizId = mo.getId() + "-" + dayKey;
            // 日节流：今日已推过（含已读）→ 跳过；纯读判定，不写业务数据
            Long sent = noticeDao.selectCount(new LambdaQueryWrapper<SysNotice>()
                    .eq(SysNotice::getBizType, BIZ_DUE)
                    .eq(SysNotice::getBizId, bizId));
            if (sent != null && sent > 0) {
                continue;
            }
            String title = "到期未派工单提醒";
            String content = String.format(
                    "工单 %s 计划开工日 %s 已到，仍有 %d 道工序未派工，请及时处理（%s）",
                    mo.getMoNo(), mo.getPlanStartDate(), pending, mo.getId());
            for (String role : ROLES) {
                noticeService.push(role, null, title, content, BIZ_DUE, bizId);
            }
            pushed++;
        }
        if (pushed > 0) {
            log.info("到期未派扫描完成：推送 {} 张工单", pushed);
        }
        return pushed;
    }
}
