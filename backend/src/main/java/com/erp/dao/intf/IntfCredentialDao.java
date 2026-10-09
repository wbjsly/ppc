package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface IntfCredentialDao extends BaseMapper<IntfCredential> {

    @Select("SELECT * FROM erp_intf_credential WHERE API_KEY = #{apiKey} AND DEL_FLAG = '0' LIMIT 1")
    IntfCredential selectByApiKey(@Param("apiKey") String apiKey);

    /** 同调用方处于 ACTIVE 的凭证（签发时 ≤2 套硬阻断，SOP-5.5-B 步骤1） */
    @Select("SELECT * FROM erp_intf_credential WHERE PARTNER_CODE = #{partnerCode} " +
            "AND STATUS = 'ACTIVE' AND DEL_FLAG = '0' ORDER BY CREATE_DATE DESC")
    List<IntfCredential> selectActiveByPartner(@Param("partnerCode") String partnerCode);

    /** 到期治理：ACTIVE 且到期日在 N 天内 / 已到期 */
    @Select("SELECT * FROM erp_intf_credential WHERE STATUS IN ('ACTIVE','DEPRECATED') AND DEL_FLAG = '0' " +
            "AND EXPIRE_AT IS NOT NULL AND EXPIRE_AT <= #{deadline}")
    List<IntfCredential> selectDue(@Param("deadline") java.time.LocalDateTime deadline);

    /** 一次性分发链接令牌反查 */
    @Select("SELECT * FROM erp_intf_credential WHERE DOWNLOAD_TOKEN = #{token} AND DEL_FLAG = '0' LIMIT 1")
    IntfCredential selectByToken(@Param("token") String token);

    /** 灰度观察期已满的新凭证（GRAY_FROM + N 小时 ≤ now） */
    @Select("SELECT * FROM erp_intf_credential WHERE OLD_CRED_ID IS NOT NULL AND GRAY_FROM IS NOT NULL " +
            "AND DEL_FLAG = '0' AND GRAY_FROM <= #{deadline} AND STATUS = 'ACTIVE'")
    List<IntfCredential> selectGrayObserveReady(@Param("deadline") java.time.LocalDateTime deadline);

    /** DEPRECATED 回滚窗口已结束 */
    @Select("SELECT * FROM erp_intf_credential WHERE STATUS = 'DEPRECATED' AND DEL_FLAG = '0' " +
            "AND DEPRECATE_UNTIL IS NOT NULL AND DEPRECATE_UNTIL <= #{now}")
    List<IntfCredential> selectDeprecateExpired(@Param("now") java.time.LocalDateTime now);

    /** 清空临时明文与一次性链接（MP updateById 忽略 null，须显式 UPDATE） */
    @Update("UPDATE erp_intf_credential SET PENDING_SECRET = NULL, DOWNLOAD_TOKEN = NULL, " +
            "DOWNLOAD_TOKEN_AT = NULL WHERE ID = #{id}")
    int clearSecretAndToken(@Param("id") String id);

    @Update("UPDATE erp_intf_credential SET PENDING_SECRET = NULL WHERE ID = #{id}")
    int clearPendingSecret(@Param("id") String id);

    /** 临时明文超期未确认（避免明文长期留库） */
    @Select("SELECT * FROM erp_intf_credential WHERE PENDING_SECRET IS NOT NULL AND DEL_FLAG = '0' " +
            "AND CREATE_DATE <= #{cutoff}")
    List<IntfCredential> selectStalePendingSecret(@Param("cutoff") java.time.LocalDateTime cutoff);
}
