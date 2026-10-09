package com.erp.dao.inv;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.inv.InvDocType;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 出入库业务类型 DAO（spec stock-doc-type）。
 */
public interface InvDocTypeDao extends BaseMapper<InvDocType> {

    /**
     * 按类型码取启用类型（引擎/作业台读路径；停用返回 null 由调用方区分标记）。
     */
    @Select("SELECT * FROM erp_inv_doc_type WHERE TYPE_CODE = #{typeCode} AND DEL_FLAG = '0'")
    InvDocType selectByCode(@Param("typeCode") String typeCode);
}
