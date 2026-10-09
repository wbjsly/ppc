package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvBin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvBinDao extends BaseMapper<InvBin> {

    /** 指定编号集合中已存在的（批量规划冲突预检，design D4） */
    @Select("<script>" +
            "SELECT BIN_CODE FROM erp_inv_bin WHERE BIN_CODE IN " +
            "<foreach item='c' collection='codes' open='(' separator=',' close=')'>#{c}</foreach>" +
            "</script>")
    List<String> selectExistingCodes(@Param("codes") List<String> codes);
}
