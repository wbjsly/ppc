package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.RedFlushApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RedFlushApplyDao extends BaseMapper<RedFlushApply> {

    /** 既有申请单号（FSAP-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT APPLY_NO FROM erp_fin_red_flush_apply WHERE APPLY_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /**
     * 频繁红冲窗口计数（FR-4.14-4-2）：同客户窗口内已进入实质流程的申请数
     * （ISSUED/APPROVED/INFO_ISSUED——排除 DRAFT/REJECTED，防草稿刷次数）。
     */
    @Select("SELECT COUNT(*) FROM erp_fin_red_flush_apply "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG = '0' "
            + "AND STATUS IN ('ISSUED','APPROVED','INFO_ISSUED') "
            + "AND CREATE_DATE >= #{since}")
    int countFreqByCustomerSince(@Param("customerId") String customerId,
                                 @Param("since") java.time.LocalDateTime since);
}
