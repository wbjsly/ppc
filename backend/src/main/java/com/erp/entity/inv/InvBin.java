package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 仓库仓位（4.1.2，spec warehouse-zone-planning，design D1/D2）：
 * 编号 = 区域码-排2位-列2位-层2位（系统生成，创建后不可改，字典序 = 物理路径序）；
 * 属性继承区域默认后可单独覆盖（写入时物化，校验以本行为准）；
 * DC-03 对仓位无适用场景（库存粒度不含仓位）→ 不建版本表（偏差表 #4）。
 */
@Getter
@Setter
@TableName("erp_inv_bin")
public class InvBin extends BaseEntity {

    private String whCode;

    private String zoneCode;

    /** 仓位编号（系统生成，创建后不可改） */
    private String binCode;

    /** 排 */
    private Integer binSeq;

    /** 列 */
    private Integer colNo;

    /** 层 */
    private Integer layerNo;

    // ---- 继承区域默认后可覆盖（字典 code） ----

    private String binType;

    private String tempLevel;

    private String hazardLevel;

    private String cleanLevel;

    /** 托位容量（NULL=不限；不做体积匹配，偏差表 #1） */
    private Integer capacityPallet;

    /** 1 启用 / 0 停用 */
    private String status;

    private String remark;
}
