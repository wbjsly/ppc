package com.erp.service.scm;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CPFR 可注入时钟（design D2）：所有 48h/72h/到期/冻结区判定经此取时间，
 * 测试以 setClock(Clock.fixed(...)) 固定时钟，不依赖真实 cron。
 */
@Component
public class CpfrClockService {

    private volatile Clock clock = Clock.systemDefaultZone();

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 当前周的周一（ZONE 判定基准，design D3） */
    public LocalDate currentMonday() {
        return today().with(java.time.DayOfWeek.MONDAY);
    }

    /** 测试替身注入 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
