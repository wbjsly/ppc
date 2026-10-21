package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamSafetyLock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EamSafetyLockDao extends BaseMapper<EamSafetyLock> {

    /**
     * 当日最大流水（LOCK + yyyyMMdd + - 共 13 字符，后缀自第 14 位起；唯一索引兜底并发）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(LOCK_NO, 14) AS UNSIGNED)) FROM erp_eam_safety_lock " +
            "WHERE LOCK_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 资产未解除挂锁（BR-4.13-16 阻断数据源） */
    @Select("SELECT * FROM erp_eam_safety_lock WHERE CARD_ID = #{cardId} " +
            "AND STATUS = 'ACTIVE' AND DEL_FLAG = '0' ORDER BY LOCK_TIME DESC")
    List<EamSafetyLock> selectActiveByCard(@Param("cardId") String cardId);
}
