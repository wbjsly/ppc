package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfMessageDao extends BaseMapper<IntfMessage> {

    /** 报文号流水：IM+yyyyMMdd+000001（前缀 10 字符） */
    @Select("SELECT MAX(CAST(SUBSTR(MSG_NO, 11) AS UNSIGNED)) FROM erp_intf_message " +
            "WHERE MSG_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    @Select("SELECT * FROM erp_intf_message WHERE IDEMPOTENCY_KEY = #{key} AND DEL_FLAG = '0' LIMIT 1")
    IntfMessage selectByIdempotencyKey(@Param("key") String key);
}
