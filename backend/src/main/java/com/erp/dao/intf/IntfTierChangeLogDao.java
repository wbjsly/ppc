package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfTierChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 档位变更历史 DAO。历史不可改删（spec 12.3.2）：本 DAO 仅暴露 INSERT（BaseMapper.insert）
 * 与只读查询，不提供 update/delete 调用路径。
 */
@Mapper
public interface IntfTierChangeLogDao extends BaseMapper<IntfTierChangeLog> {

    /** 历史分页/筛选（按调用方，时间倒序） */
    @Select("<script>" +
            "SELECT * FROM erp_intf_tier_change_log WHERE DEL_FLAG = '0'" +
            "<if test='caller != null and caller != \"\"'> AND CALLER = #{caller}</if>" +
            " ORDER BY CREATE_DATE DESC, ID DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<IntfTierChangeLog> selectHistory(@Param("caller") String caller,
                                          @Param("offset") long offset,
                                          @Param("limit") long limit);

    @Select("<script>" +
            "SELECT COUNT(*) FROM erp_intf_tier_change_log WHERE DEL_FLAG = '0'" +
            "<if test='caller != null and caller != \"\"'> AND CALLER = #{caller}</if>" +
            "</script>")
    long countHistory(@Param("caller") String caller);
}
