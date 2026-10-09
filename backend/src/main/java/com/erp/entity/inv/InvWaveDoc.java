package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 波次单据归属（4.8.1/4.8.2，spec wave-management）：
 * 1 单 1 行；拆出/作废 = 置 UNBOUND 保留行（BR-4.4-47 / C-0-05 不物理删除）。
 * 分播（SORT_STATUS/SORT_RESULT 快照）与装车（LOAD_STATUS）状态、发运失败留痕（SHIP_ERR 重试）。
 */
@Getter
@Setter
@TableName("erp_inv_wave_doc")
public class InvWaveDoc extends BaseEntity {

    public static final String BIND_BOUND = "BOUND";
    public static final String BIND_UNBOUND = "UNBOUND";

    public static final String SORT_PENDING = "PENDING";
    public static final String SORT_PASSED = "PASSED";

    public static final String LOAD_PENDING = "PENDING";
    public static final String LOAD_LOADED = "LOADED";

    private String waveId;

    private String shipId;

    private String shipNo;

    /** BOUND 在波次内 / UNBOUND 已拆出 */
    private String bindStatus;

    private String unboundReason;

    /** PENDING → PASSED（分播复核通过） */
    private String sortStatus;

    /** 复核结果快照 JSON（装车比对基准，design D6） */
    private String sortResult;

    private String sortBy;
    private LocalDateTime sortAt;

    /** PENDING → LOADED（装车比对通过，C-4.4-06） */
    private String loadStatus;

    private String loadBy;
    private LocalDateTime loadAt;

    /** 最近一次发运过账失败原因（BR-4.4-46 单独重试） */
    private String shipErr;
}
