package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.Reimburse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReimburseDao extends BaseMapper<Reimburse> {

    /** 同期最大流水（报销单号 EXP+yyyyMMdd 推号） */
    @Select("SELECT MAX(CAST(SUBSTRING(EXP_NO, 11) AS UNSIGNED)) FROM erp_fin_reimburse "
            + "WHERE EXP_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG = '0'")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
