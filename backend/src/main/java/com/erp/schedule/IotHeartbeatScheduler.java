package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.iot.IotDeviceDao;
import com.erp.entity.iot.IotDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * IoT 设备心跳超时扫描（add-iot-mobile 13.6，FR-4.11-5-2 异常处理①）：
 * 心跳丢失 > IOT_HEARTBEAT_TIMEOUT_MIN（默认 5 分钟）→ 置 OFFLINE。
 * 仿真器/网关打点更新 HEARTBEAT_AT；本任务仅做超时降级。
 */
@Slf4j
@Component
public class IotHeartbeatScheduler {

    private final IotDeviceDao deviceDao;

    public IotHeartbeatScheduler(IotDeviceDao deviceDao) {
        this.deviceDao = deviceDao;
    }

    /** 每 60s 扫描一次心跳超时 */
    @Scheduled(fixedDelayString = "${iot.heartbeat-scan-ms:60000}")
    public void sweep() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        var stale = deviceDao.selectList(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getStatus, IotDevice.ST_ONLINE)
                .and(w -> w.isNull(IotDevice::getHeartbeatAt)
                        .or().lt(IotDevice::getHeartbeatAt, threshold)));
        for (IotDevice d : stale) {
            d.setStatus(IotDevice.ST_OFFLINE);
            deviceDao.updateById(d);
            log.info("[iot] 心跳超时置离线：{}（丢失 > 5 分钟）", d.getDeviceNo());
        }
    }

    /** 手动触发（冒烟/测试入口）：返回离线设备数 */
    public long runOnce() {
        sweep();
        return deviceDao.selectCount(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getStatus, IotDevice.ST_OFFLINE));
    }

    static long minutesSince(LocalDateTime t) {
        return ChronoUnit.MINUTES.between(t, LocalDateTime.now());
    }
}
