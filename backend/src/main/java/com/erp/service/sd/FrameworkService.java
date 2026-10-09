package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.Framework;
import com.erp.entity.sd.FrameworkRelease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 销售框架协议（tasks 13.2~13.6，spec sales-framework-agreement，D11 独立对象）。
 *
 * 口径：
 *  - FW_NO 系统生成且创建后不可改（spec 编码锁定）；状态机 EFFECTIVE/TERMINATED/EXPIRED；
 *  - 余量：剩余可下达 = 行总量 − 已下达；剩余可发 = 已下达 − 已发（13.3 实时回写）；
 *  - C-4.3-10 L1 硬阻断：下达超总量、发货超已下达（提示剩余量，不允许部分越量）；
 *  - S-4.3-11 变更与终止：总量调整（不低于已发量）/单价重谈（前后值留痕）/提前终止
 *    （冻结后续发货），一律经销售总监 L2 审批后执行；
 *  - 3.11.2 执行视图：按行展示三量与分批时间表，并从视图发起分批发货（复用发货能力，
 *    单次发货拆行策略属 3.7.3，本页不承载）。
 */
public interface FrameworkService {

    Page<Framework> page(long current, long size, String keyword, String status,
                         String customerId);

    /** 详情：头 + 行（三量与余量）+ 下达单 + 变更历史 + 审批状态 */
    Map<String, Object> detail(String id);

    /** 13.2 创建（FW_NO 生成锁定；行含物料、总量、锁定价） */
    Framework create(Map<String, Object> req);

    /** 13.2 更新：编号不可改；已下达行的量/价不可改、不可删（未下达行可改） */
    Framework update(String id, Map<String, Object> req);

    /** 13.3 下达框架订单：回写已下达量；超剩余可下达量 L1 阻断（C-4.3-10） */
    FrameworkRelease release(String id, String lineId, BigDecimal qty, LocalDate deliverDate);

    /** 取消未发货的下达单（已发量必须为 0），回退已下达量 */
    FrameworkRelease cancelRelease(String releaseId, String reason);

    /**
     * 13.6 从执行视图发起分批发货：校验协议有效（终止/过期冻结）与本单剩余可发，
     * 生成 FRAMEWORK 发货单同事务过账（库存不足 422），回写下达单与协议行已发量。
     */
    Map<String, Object> shipFromRelease(String releaseId, BigDecimal qty);

    /**
     * 13.5 变更/终止发起（S-4.3-11）：TOTAL 总量调整（新量 ≥ 已发量）/
     * PRICE 单价重谈（前后值留痕）/ TERMINATE 提前终止（通过后冻结后续发货）。
     * 存 PENDING_CHANGE 并提交销售总监 L2 审批，通过后由回调执行。
     */
    Framework change(String id, Map<String, Object> payload);

    /** 13.6 分批执行视图：行三量余量 + 下达时间表 + 变更历史 */
    Map<String, Object> execution(String id);
}
