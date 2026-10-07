package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 令牌桶限流窗口状态（design D2：DB 计数，测试可断言、重启不丢）。 */
@Getter
@Setter
@TableName("erp_intf_rate_state")
public class IntfRateState extends BaseEntity {

    private String caller;
    /** yyyyMMddHHmm 统计窗口（1 分钟） */
    private String windowMin;
    private Integer hitCount;
    private Integer limitPerMin;
    /** 桶容量 = 限值 × 1.5 */
    private Integer bucketCapacity;
    /** 本窗口 429 次数（BR-5.5-06 连续 3 窗口降档判定） */
    private Integer overCount;
    private LocalDateTime lastOverAt;
}
