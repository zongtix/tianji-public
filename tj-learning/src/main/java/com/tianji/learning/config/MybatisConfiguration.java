package com.tianji.learning.config;

import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.tianji.learning.utils.TableInfoContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisConfiguration {
    @Bean
    public DynamicTableNameInnerInterceptor dynamicTableNameInnerInterceptor(){
        return new DynamicTableNameInnerInterceptor((sql, tableName) -> {
            if (!"points_board".equals(tableName)) {
                return tableName;
            }
            String targetTableName = TableInfoContext.getInfo();
            return targetTableName == null || targetTableName.isBlank() ? tableName : targetTableName;
        });
    }
}
