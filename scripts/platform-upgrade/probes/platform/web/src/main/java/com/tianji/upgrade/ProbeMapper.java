package com.tianji.upgrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProbeMapper {
    @Select("SELECT amount FROM platform_upgrade_probe WHERE id = #{id}")
    Integer amount(@Param("id") long id);
}
