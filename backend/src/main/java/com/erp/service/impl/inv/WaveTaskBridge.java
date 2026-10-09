package com.erp.service.impl.inv;

import com.erp.service.inv.PickTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WaveService ↔ PickTaskService 桥接（spec wave-management design D5，任务 5.1）：
 * 波次确认分配生成 WAVE 任务、波次作废撤销任务——打破 Wave→PickTask 直接依赖
 * （PickTaskServiceImpl 已 @Lazy 注入 WaveService 做状态连带，双直连会构造环）。
 */
@Slf4j
@Component
public class WaveTaskBridge implements WaveServiceImpl.WaveTaskOps {

    private final PickTaskService pickTaskService;

    public WaveTaskBridge(PickTaskService pickTaskService) {
        this.pickTaskService = pickTaskService;
    }

    @Override
    public void cancelIfExists(String waveNo, String reason) {
        try {
            pickTaskService.cancelIfExists("WAVE", waveNo, reason);
        } catch (RuntimeException e) {
            log.warn("cancel WAVE task {} failed (non-blocking): {}", waveNo, e.getMessage());
        }
    }

    @Override
    public void onTaskStatus(String waveNo, String taskStatus) {
        // 波次状态由 PickTaskServiceImpl.transition 直接回调 WaveService，桥不重复转发
    }

    @Override
    public void createFromWave(String waveId) {
        pickTaskService.createFromWave(waveId);
    }
}
