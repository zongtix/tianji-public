package com.tianji.course.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CourseIndexSnapshotTest {
    @Test
    void pagesPublishedRowsByLongIdIncludingSoftDeletedRowsAndOnlyPublishedContent() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE course(id BIGINT PRIMARY KEY,name VARCHAR(80),status INT,deleted INT,"
                + "purchase_start_time TIMESTAMP,purchase_end_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE course_content(id BIGINT PRIMARY KEY,course_introduce VARCHAR(100),"
                + "use_people VARCHAR(100),course_detail VARCHAR(100),deleted INT)");
        jdbc.update("INSERT INTO course VALUES(10,'下架课',3,0,NULL,NULL),(20,'已删除课',2,1,NULL,NULL),"
                + "(1589888774267072513,'已发布课',2,0,NULL,NULL)");
        jdbc.update("INSERT INTO course_content VALUES(10,'介绍','基础','旧正文',1),"
                + "(1589888774267072513,'正式介绍','后端基础','正式正文',0)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(CourseMapper.class);
        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
            var mapper = session.getMapper(CourseMapper.class);
            var first = mapper.selectSemanticIndexPage(0, 2);
            assertThat(first).extracting(value -> value.getId()).containsExactly(10L, 20L);
            assertThat(first.get(0).getDetail()).isNull();
            assertThat(first.get(1).getDeleted()).isEqualTo(1);
            var next = mapper.selectSemanticIndexPage(20, 2);
            assertThat(next).hasSize(1);
            assertThat(next.get(0).getId()).isEqualTo(1589888774267072513L);
            assertThat(next.get(0).getIntroduce()).isEqualTo("正式介绍");
            assertThat(next.get(0).getUsePeople()).isEqualTo("后端基础");
            assertThat(next.get(0).getDetail()).isEqualTo("正式正文");
            assertThat(mapper.selectSemanticIndexInfo(20L).getDeleted()).isEqualTo(1);
            assertThat(mapper.selectSemanticIndexInfo(999L)).isNull();
            assertThat(mapper.selectSemanticIndexPage(next.get(0).getId(), 2)).isEmpty();
        }
    }
}
