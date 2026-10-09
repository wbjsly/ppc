package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvCheckReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 恒等式校验报告 DAO（spec stock-snapshot）。
 */
public interface InvCheckReportDao extends BaseMapper<InvCheckReport> {

    /**
     * 最近一次运行（4.3.1 「待核实」标记来源）。
     */
    @Select("SELECT * FROM erp_inv_check_report WHERE DEL_FLAG = '0' "
            + "ORDER BY RUN_DATE DESC LIMIT 1")
    InvCheckReport selectLatest();

    /**
     * 指定运行日是否已有记录（按日幂等：存在即跳过/覆盖由服务层决定）。
     */
    @Select("SELECT COUNT(*) FROM erp_inv_check_report WHERE RUN_DATE = #{runDate} "
            + "AND DEL_FLAG = '0'")
    long countByRunDate(@Param("runDate") LocalDate runDate);
}
