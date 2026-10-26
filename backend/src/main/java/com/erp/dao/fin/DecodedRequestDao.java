package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.DecodedRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DecodedRequestDao extends BaseMapper<DecodedRequest> {

    /** 幂等键是否已 ISSUED（BR-4.14-37 重复 422 判据） */
    @Select("SELECT COUNT(*) FROM erp_decoded_request WHERE DEL_FLAG = '0' "
            + "AND IDEMPOTENT_KEY = #{key} AND STATUS = 'ISSUED'")
    int countIssuedByKey(@Param("key") String idempotentKey);
}
