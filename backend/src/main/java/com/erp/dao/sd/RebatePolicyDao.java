package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.RebatePolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface RebatePolicyDao extends BaseMapper<RebatePolicy> {

    /**
     * 生效阶梯（客户专属优先，否则全局默认），按达成率下限升序。
     * 无客户维度则返回全局政策；有效期内（VALID_FROM/VALID_TO 可空）。
     */
    @Select("SELECT * FROM erp_sd_rebate_policy WHERE DEL_FLAG='0' AND STATUS='EFFECTIVE' "
            + "AND (CUSTOMER_ID = #{customerId} OR CUSTOMER_ID IS NULL) "
            + "AND (VALID_FROM IS NULL OR VALID_FROM <= #{today}) "
            + "AND (VALID_TO IS NULL OR VALID_TO >= #{today}) "
            + "ORDER BY CUSTOMER_ID IS NULL, BAND_FROM")
    List<RebatePolicy> selectEffective(@Param("customerId") String customerId,
                                       @Param("today") LocalDate today);
}
