package com.erp.schedule;

import com.erp.service.intf.ContractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 接入治理调度（spec interface-onboarding 6.3/6.2：评审超时催办 + 废弃倒计时按日预告）。 */
@Slf4j
@Component
public class InterfaceOnboardScheduler {

    private final ContractService contractService;

    public InterfaceOnboardScheduler(ContractService contractService) {
        this.contractService = contractService;
    }

    @Scheduled(fixedDelayString = "${app.intf.onboard-sweep-ms:3600000}")
    public void sweep() {
        try {
            int n = contractService.sweep();
            if (n > 0) {
                log.info("interface onboarding sweep done: handled={}", n);
            }
        } catch (Exception e) {
            log.warn("interface onboarding sweep failed: {}", e.getMessage());
        }
    }
}
