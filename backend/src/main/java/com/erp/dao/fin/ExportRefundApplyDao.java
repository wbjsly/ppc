package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ExportRefundApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportRefundApplyDao extends BaseMapper<ExportRefundApply> {

    /** 既有退税申报单号（RFA-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT APPLY_NO FROM erp_fin_export_refund_apply WHERE APPLY_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 同期间是否存在退回补正挂起单（挂起时暂停后续批次提交，FR-4.14-7-4 异常处理） */
    @Select("SELECT COUNT(*) FROM erp_fin_export_refund_apply "
            + "WHERE PERIOD = #{period} AND STATUS = 'RETURNED' AND DEL_FLAG = '0'")
    int countReturnedInPeriod(@Param("period") String period);
}
