package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamReworkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 技改大修单 DAO（add-asset-rework）。
 */
@Mapper
public interface EamReworkOrderDao extends BaseMapper<EamReworkOrder> {

    /** 同卡进行中技改单（重复发起阻断：一卡一单唯一链） */
    @Select("SELECT * FROM erp_eam_rework_order WHERE DEL_FLAG = '0' "
            + "AND CARD_ID = #{cardId} "
            + "AND REWORK_STATUS IN ('DRAFT','PENDING_JUDGE','JUDGED','IN_REWORK','COMPLETED') LIMIT 1")
    EamReworkOrder selectInFlightByCard(@Param("cardId") String cardId);

    /** 卡片在建且特批续提的进行中单（折旧引擎第三分支取数） */
    @Select("SELECT * FROM erp_eam_rework_order WHERE DEL_FLAG = '0' "
            + "AND CARD_ID = #{cardId} AND REWORK_STATUS = 'IN_REWORK' "
            + "AND CONTINUE_DEPR_FLAG = '1' LIMIT 1")
    EamReworkOrder selectInReworkWithContinuedDepr(@Param("cardId") String cardId);
}
