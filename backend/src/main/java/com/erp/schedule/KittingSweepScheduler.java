package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.service.mrp.KittingService;
import com.erp.service.SysParamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 齐套自动重检调度（change add-kitting-check design D3，spec kitting-check「自动重检调度」）：
 * 间隔扫「未关闭工单」（RELEASED/HOLD——双向：到货恢复 + 领料新缺料，只扫缺料标记会漏后者），
 * 逐张串行 check(moId, notifyOnFlip=true)——翻转推站内通知（design D2）。
 *
 * 并发互斥：check 是重建式删除，两个 check 同跑会互踩。
 * Spring @Scheduled 单线程调度 + 本方法 synchronized → 与调度自身互斥；
 * 人工批量校验侧在服务层串行 + 批次内去重（design D3）。
 * 多实例部署需补分布式锁——本项目单实例 Docker（design Risks）。
 */
@Slf4j
@Component
public class KittingSweepScheduler {

    private final KittingService kittingService;
    private final MrpMoDao moDao;
    private final SysParamService sysParam;

    public KittingSweepScheduler(KittingService kittingService, MrpMoDao moDao,
                                 SysParamService sysParam) {
        this.kittingService = kittingService;
        this.moDao = moDao;
        this.sysParam = sysParam;
    }

    /** 默认 5 分钟（fixedDelayString 可由 app.kitting.sweep-ms 覆盖）；间隔微调走 sys_param */
    @Scheduled(fixedDelayString = "${app.kitting.sweep-ms:300000}")
    public void sweep() {
        try {
            int minutes = sysParam.getInt("KITTING_SWEEP_INTERVAL_MIN", 5);
            if (minutes <= 0) {
                return; // 参数停用（0=停）
            }
            doSweep();
        } catch (Exception e) {
            log.warn("齐套自动重检失败（下轮重试）：{}", e.getMessage());
        }
    }

    /**
     * 扫未关闭工单逐张重算（测试可直接调用；synchronized 防重入——check 重建式互踩）。
     * 返回本轮实际重算的工单数。
     */
    public synchronized int doSweep() {
        List<MrpMo> mos = moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .in(MrpMo::getStatus, MrpMo.ST_RELEASED, MrpMo.ST_HOLD)
                .orderByAsc(MrpMo::getCreateDate));
        int checked = 0;
        for (MrpMo mo : mos) {
            try {
                kittingService.check(mo.getId(), true);
                checked++;
            } catch (Exception e) {
                log.warn("工单齐套重检失败：{} err={}", mo.getMoNo(), e.getMessage());
            }
        }
        if (checked > 0) {
            log.info("齐套自动重检完成：扫描={} 张", checked);
        }
        return checked;
    }
}
