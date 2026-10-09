package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfRateState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IntfRateStateDao extends BaseMapper<IntfRateState> {

    /**
     * 原子累加本窗口命中数（design D2：MySQL upsert，无需先查后写）。
     * 新窗口落行时 HIT_COUNT 从 0 起，累加后即本次请求序号。
     */
    @Insert("INSERT INTO erp_intf_rate_state (ID, CALLER, WINDOW_MIN, HIT_COUNT, LIMIT_PER_MIN, BUCKET_CAPACITY, OVER_COUNT) " +
            "VALUES (#{id}, #{caller}, #{windowMin}, #{hit}, #{limitPerMin}, #{bucketCapacity}, 0) " +
            "ON DUPLICATE KEY UPDATE HIT_COUNT = HIT_COUNT + #{hit}")
    int bump(@Param("id") String id, @Param("caller") String caller, @Param("windowMin") String windowMin,
             @Param("hit") int hit, @Param("limitPerMin") int limitPerMin, @Param("bucketCapacity") int bucketCapacity);

    @Select("SELECT * FROM erp_intf_rate_state WHERE CALLER = #{caller} AND WINDOW_MIN = #{windowMin} LIMIT 1")
    IntfRateState selectWindow(@Param("caller") String caller, @Param("windowMin") String windowMin);

    /** 触发 429 时记一次超限（BR-5.5-06 连续 3 窗口降档判定依据） */
    @Update("UPDATE erp_intf_rate_state SET OVER_COUNT = OVER_COUNT + 1, LAST_OVER_AT = NOW() " +
            "WHERE CALLER = #{caller} AND WINDOW_MIN = #{windowMin}")
    int markOver(@Param("caller") String caller, @Param("windowMin") String windowMin);

    /** 连续 N 个窗口内出现超限的窗口数（含当前窗口） */
    @Select("SELECT COUNT(DISTINCT WINDOW_MIN) FROM erp_intf_rate_state " +
            "WHERE CALLER = #{caller} AND OVER_COUNT > 0 AND WINDOW_MIN IN (#{w1}, #{w2}, #{w3})")
    int countOverWindows(@Param("caller") String caller, @Param("w1") String w1,
                         @Param("w2") String w2, @Param("w3") String w3);

    /** 吊销/降档时清零令牌桶（BR-5.5-15 紧急吊销 3 秒生效） */
    @Update("UPDATE erp_intf_rate_state SET HIT_COUNT = 0, OVER_COUNT = 0 WHERE CALLER = #{caller}")
    int clearBucket(@Param("caller") String caller);
}
