package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmExchangeRate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MdmExchangeRateDao extends BaseMapper<MdmExchangeRate> {

    /**
     * 同序列（币对×类型）记录按生效日排序（design D2 判定输入）。
     * excludeId 用于编辑时排除自身。
     */
    @Select("<script>" +
            "SELECT * FROM erp_mdm_exchange_rate WHERE DEL_FLAG = '0' " +
            "AND BASE_CCY = #{base} AND QUOTE_CCY = #{quote} AND RATE_TYPE = #{rateType} " +
            "<if test='excludeId != null and excludeId != \"\"'> AND ID != #{excludeId}</if> " +
            "ORDER BY EFFECTIVE_DATE" +
            "</script>")
    List<MdmExchangeRate> selectSequence(@Param("base") String base,
                                         @Param("quote") String quote,
                                         @Param("rateType") String rateType,
                                         @Param("excludeId") String excludeId);

    /** 试算命中：目标日期落在闭区间内（按类型优先级由服务层排序取一） */
    @Select("SELECT * FROM erp_mdm_exchange_rate WHERE DEL_FLAG = '0' " +
            "AND BASE_CCY = #{base} AND QUOTE_CCY = #{quote} AND RATE_TYPE = #{rateType} " +
            "AND EFFECTIVE_DATE <= #{date} AND EXPIRE_DATE >= #{date} " +
            "ORDER BY EFFECTIVE_DATE DESC LIMIT 1")
    MdmExchangeRate selectHit(@Param("base") String base, @Param("quote") String quote,
                              @Param("rateType") String rateType, @Param("date") LocalDate date);

    /** 某币对是否存在任何记录（试算缺失原因区分用） */
    @Select("SELECT COUNT(1) FROM erp_mdm_exchange_rate WHERE DEL_FLAG = '0' " +
            "AND BASE_CCY = #{base} AND QUOTE_CCY = #{quote}")
    int countPair(@Param("base") String base, @Param("quote") String quote);
}
