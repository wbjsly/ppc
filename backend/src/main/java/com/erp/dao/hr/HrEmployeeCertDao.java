package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrEmployeeCert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrEmployeeCertDao extends BaseMapper<HrEmployeeCert> {

    /** 关键类别（IS_KEY=1）的已失效证书（排班门禁判定源）。 */
    @Select("SELECT c.* FROM hr_employee_cert c "
            + "JOIN hr_cert_category g ON c.CATE_ID = g.ID "
            + "WHERE c.STATUS = 'EXPIRED' AND g.IS_KEY = '1' "
            + "AND c.DEL_FLAG = '0' AND g.DEL_FLAG = '0'")
    List<HrEmployeeCert> selectExpiredKeyCerts();
}
