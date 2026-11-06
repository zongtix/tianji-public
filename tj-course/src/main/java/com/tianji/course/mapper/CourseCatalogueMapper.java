package com.tianji.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.course.domain.po.CourseCatalogue;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 目录草稿 Mapper 接口
 * </p>
 *
 * @author wusongsong
 * @since 2022-07-19
 */
public interface CourseCatalogueMapper extends BaseMapper<CourseCatalogue> {

    // 不读草稿、练习、媒资；小节必须属于当前课程中有效的正式章。
    @Select("""
            <script>
            SELECT cc.course_id AS courseId,
                   CASE WHEN cc.type=1 THEN cc.name ELSE CONCAT(ch.name,' / ',cc.name) END AS title
            FROM course_catalogue cc
            LEFT JOIN course_catalogue ch ON ch.id=cc.parent_catalogue_id
                AND ch.course_id=cc.course_id AND ch.type=1 AND ch.deleted=0 AND ch.parent_catalogue_id=0
            WHERE cc.deleted=0 AND TRIM(cc.name)&lt;&gt;'' AND cc.course_id IN
            <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND ((cc.type=1 AND cc.parent_catalogue_id=0)
                   OR (cc.type=2 AND ch.id IS NOT NULL AND TRIM(ch.name)&lt;&gt;''))
            ORDER BY cc.course_id,COALESCE(ch.c_index,cc.c_index),COALESCE(ch.id,cc.id),cc.type,cc.c_index,cc.id
            </script>
            """)
    List<OutlineTitle> selectOutlineTitles(@Param("courseIds") List<Long> courseIds);

    record OutlineTitle(Long courseId, String title) { }

}
