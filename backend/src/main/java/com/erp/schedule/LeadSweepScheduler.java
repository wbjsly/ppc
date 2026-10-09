package com.erp.schedule;

import com.erp.service.crm.LeadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 线索调度（spec crm-lead-management，FR-4.8-1-3 / FR-4.8-1-4 异常处理）：
 *  - 分配后 3 个工作日未跟进 → 标记提醒（页面提示销售经理）
 *  - 超过 30 天未跟进 → 自动回收至线索池（留痕入池原因 RECYCLE）
 * 单实例部署假设（本地 Docker），无分布式锁；调度线程无登录上下文，服务内部不做角色校验。
 */
@Slf4j
@Component
public class LeadSweepScheduler {

    private final LeadService leadService;

    public LeadSweepScheduler(LeadService leadService) {
        this.leadService = leadService;
    }

    /** 每日 08:00 扫描（业务高峰前完成提醒与回收） */
    @Scheduled(cron = "0 0 8 * * ?")
    public void sweepLeads() {
        try {
            Map<String, Integer> r = leadService.sweep();
            if (r.get("reminded") > 0 || r.get("recycled") > 0) {
                log.info("lead sweep done: reminded={}, recycled={}", r.get("reminded"), r.get("recycled"));
            }
        } catch (Exception e) {
            log.warn("lead sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
