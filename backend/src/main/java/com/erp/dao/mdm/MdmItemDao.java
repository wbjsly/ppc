package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface MdmItemDao extends BaseMapper<MdmItem> {

    /** 同分类最大编码，用于生成下一个 {前缀}{4位分类码}{6位流水}（前缀长 = prefixLen(=前缀+4)，流水恒 6 位） */
    @Select("SELECT MAX(ITEM_CODE) FROM erp_mdm_item WHERE CATEGORY_CODE = #{categoryCode}")
    String selectMaxCodeByCategory(@Param("categoryCode") String categoryCode);

    /** 近似查重候选（存量 >5000 时的预筛）：名称互为前缀/包含关系，服务层再算编辑距离 ≤N */
    @Select("SELECT * FROM erp_mdm_item WHERE DEL_FLAG = '0' AND " +
            "(ITEM_NAME LIKE CONCAT(#{prefix}, '%') OR #{prefix} LIKE CONCAT(ITEM_NAME, '%')) " +
            "ORDER BY ITEM_CODE LIMIT 20")
    List<MdmItem> findSimilarCandidates(@Param("prefix") String itemName);

    /** 指向目标编码的启用源物料（替代目标停用阻断清单，LIMIT 10） */
    @Select("SELECT * FROM erp_mdm_item WHERE DEL_FLAG = '0' AND STATUS = '1' " +
            "AND ALT_ITEM_CODE = #{targetCode} ORDER BY ITEM_CODE LIMIT 10")
    List<MdmItem> findSourcesByTarget(@Param("targetCode") String targetCode);

    /** 指向目标编码的源物料总数（阻断提示用） */
    @Select("SELECT COUNT(1) FROM erp_mdm_item WHERE DEL_FLAG = '0' AND ALT_ITEM_CODE = #{targetCode}")
    int countSourcesByTarget(@Param("targetCode") String targetCode);

    /** 替代关系列表总数（direction 口径须与列表 SQL 一致，否则分页 total 失真） */
    @Select("<script>" +
            "SELECT COUNT(1) FROM erp_mdm_item a " +
            "LEFT JOIN erp_mdm_item b ON b.ITEM_CODE = a.ALT_ITEM_CODE AND b.DEL_FLAG = '0' " +
            "WHERE a.DEL_FLAG = '0' AND a.ALT_ITEM_CODE IS NOT NULL AND a.ALT_ITEM_CODE != '' " +
            "<if test='status != null and status != \"\"'> AND a.STATUS = #{status}</if> " +
            "<if test='keyword != null and keyword != \"\"'> " +
            "<choose>" +
            "<when test='direction == \"target\"'> AND (a.ALT_ITEM_CODE LIKE CONCAT('%',#{keyword},'%') OR b.ITEM_NAME LIKE CONCAT('%',#{keyword},'%'))</when>" +
            "<otherwise> AND (a.ITEM_CODE LIKE CONCAT('%',#{keyword},'%') OR a.ITEM_NAME LIKE CONCAT('%',#{keyword},'%'))</otherwise>" +
            "</choose></if> " +
            "</script>")
    int countSubstituteRelations(@Param("keyword") String keyword,
                                 @Param("direction") String direction,
                                 @Param("status") String status);

    /** 替代关系列表分页（自 join 返回源物料 + 目标三列冗余） */
    @Select("<script>" +
            "SELECT a.*, b.ITEM_CODE AS TARGET_CODE, b.ITEM_NAME AS TARGET_NAME, b.STATUS AS TARGET_STATUS " +
            "FROM erp_mdm_item a " +
            "LEFT JOIN erp_mdm_item b ON b.ITEM_CODE = a.ALT_ITEM_CODE AND b.DEL_FLAG = '0' " +
            "WHERE a.DEL_FLAG = '0' AND a.ALT_ITEM_CODE IS NOT NULL AND a.ALT_ITEM_CODE != '' " +
            "<if test='status != null and status != \"\"'> AND a.STATUS = #{status}</if> " +
            "<if test='keyword != null and keyword != \"\"'> " +
            "<choose>" +
            "<when test='direction == \"target\"'> AND (a.ALT_ITEM_CODE LIKE CONCAT('%',#{keyword},'%') OR b.ITEM_NAME LIKE CONCAT('%',#{keyword},'%'))</when>" +
            "<otherwise> AND (a.ITEM_CODE LIKE CONCAT('%',#{keyword},'%') OR a.ITEM_NAME LIKE CONCAT('%',#{keyword},'%'))</otherwise>" +
            "</choose></if> " +
            "ORDER BY a.ITEM_CODE LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<Map<String, Object>> selectSubstituteRelations(@Param("keyword") String keyword,
                                                        @Param("direction") String direction,
                                                        @Param("status") String status,
                                                        @Param("limit") long limit,
                                                        @Param("offset") long offset);
}
