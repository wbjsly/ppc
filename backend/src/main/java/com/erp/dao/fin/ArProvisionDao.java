package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ArProvision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ArProvisionDao extends BaseMapper<ArProvision> {

    /** 某客户某期已确认台账（冲回锚/已提余额来源） */
    @Select("SELECT * FROM erp_fin_ar_provision "
            + "WHERE PERIOD = #{period} AND CUSTOMER_ID = #{customerId} "
            + "AND STATUS = 'CONFIRMED' AND DEL_FLAG = '0' LIMIT 1")
    ArProvision selectConfirmed(@Param("period") String period,
                                @Param("customerId") String customerId);

    /** 单号（PVD+period+customerId 存在性检查用） */
    @Select("SELECT PROVISION_NO FROM erp_fin_ar_provision WHERE PROVISION_NO LIKE #{prefix}")
    java.util.List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 上一期已确认且未冲回的台账（下期计提前自动冲回——design D3 步骤②） */
    @Select("SELECT * FROM erp_fin_ar_provision "
            + "WHERE CUSTOMER_ID = #{customerId} AND STATUS = 'CONFIRMED' "
            + "AND REVERSED_FLAG = '0' AND DEL_FLAG = '0' "
            + "AND VOUCHER_ID IS NOT NULL AND PERIOD < #{period} "
            + "ORDER BY PERIOD DESC LIMIT 1")
    ArProvision selectUnreversedPrev(@Param("customerId") String customerId,
                                     @Param("period") String period);

    /** 已确认且有凭证但凭证未过账的计提（结账清单第⑤项 pendingProvision；target=0 纯冲回行 VOUCHER_ID 为空不计） */
    @Select("SELECT COUNT(*) FROM erp_fin_ar_provision p "
            + "LEFT JOIN erp_fin_voucher v ON v.ID = p.VOUCHER_ID AND v.DEL_FLAG = '0' "
            + "WHERE p.PERIOD = #{period} AND p.STATUS = 'CONFIRMED' AND p.DEL_FLAG = '0' "
            + "AND p.VOUCHER_ID IS NOT NULL AND (v.STATUS IS NULL OR v.STATUS <> 'POSTED')")
    long countUnposted(@Param("period") String period);
}
