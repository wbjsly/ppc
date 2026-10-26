package com.erp.service.fin;

import java.util.Map;

/**
 * 进项税额转出服务（M9-4-3，spec invoice-management ADDED 进项税额转出，
 * FR-4.14-3-6 / BR-4.14-22）。
 * 两模式：直接转出（选票 + 金额）与分摊转出（免税/简易项目销售额占比公式）；
 * 生成转出凭证（借成本费用 贷进项税额转出）+ 回写台账累计转出金额，累计不超已抵扣税额。
 */
public interface InputTaxTransferOutService {

    /** 直接转出：CERTIFIED 门禁 + 剩余可转出校验 + 凭证 + 台账回写 */
    Map<String, Object> directTransfer(Map<String, Object> payload);

    /**
     * 分摊转出（BR-4.14-22）：转出税额 = 当期无法划分的全部进项税额 × (免税与简易项目销售额 / 全部销售额)，
     * 按当期 CERTIFIED 发票已抵扣税额比例逐票分摊（尾差调最大票）。
     */
    Map<String, Object> apportionTransfer(Map<String, Object> payload);
}
