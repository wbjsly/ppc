package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinAccountDao extends BaseMapper<FinAccount> {

    /** 按编码取生效科目（停用/缺失由服务层 422） */
    @Select("SELECT * FROM erp_fin_account WHERE ACCOUNT_CODE = #{code} AND DEL_FLAG = '0' LIMIT 1")
    FinAccount selectActiveByCode(@Param("code") String code);
}
