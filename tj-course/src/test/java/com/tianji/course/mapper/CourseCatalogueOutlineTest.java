package com.tianji.course.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CourseCatalogueOutlineTest {
    @Test
    void batchesOnlyPublishedTitlesInChapterOrderWithoutExercisesOrMedia() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE course_catalogue(id BIGINT PRIMARY KEY,course_id BIGINT,name VARCHAR(100),
                type INT,parent_catalogue_id BIGINT,c_index INT,deleted INT)
                """);
        // 插入顺序和小节序号不能让小节跨越其所属章；同序号按 ID 稳定排序。
        jdbc.update("""
                INSERT INTO course_catalogue VALUES
                (11,10,'Spring Cloud',2,1,2,0),(12,10,'架构演变',2,1,1,0),
                (1,10,'认识微服务',1,0,1,0),(2,10,'服务调用',1,0,2,0),
                (13,10,'HTTP',2,2,1,0),(14,10,'Feign',2,2,1,0),
                (3,10,'已删章',1,0,3,1),(15,10,'已删章下的小节',2,3,1,0),
                (16,10,'已删小节',2,1,3,1),(17,10,'练习',3,1,3,0),
                (18,10,'孤立小节',2,99,1,0),(19,10,'跨课程小节',2,20,1,0),
                (20,20,'前端开发',1,0,1,0),(21,20,'HTML',2,20,1,0),
                (22,30,'范围外课程',1,0,1,0),(23,10,'',1,0,4,0),
                (24,10,'空标题章下的小节',2,23,1,0)
                """);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(CourseCatalogueMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
            var mapper = session.getMapper(CourseCatalogueMapper.class);
            var titles = mapper.selectOutlineTitles(List.of(10L, 20L));
            assertThat(titles).extracting(CourseCatalogueMapper.OutlineTitle::title).containsExactly(
                    "认识微服务", "认识微服务 / 架构演变", "认识微服务 / Spring Cloud",
                    "服务调用", "服务调用 / HTTP", "服务调用 / Feign", "前端开发", "前端开发 / HTML");
            assertThat(titles).extracting(CourseCatalogueMapper.OutlineTitle::courseId)
                    .containsExactly(10L, 10L, 10L, 10L, 10L, 10L, 20L, 20L);
            assertThat(mapper.selectOutlineTitles(List.of(99L))).isEmpty();
        }
    }
}
