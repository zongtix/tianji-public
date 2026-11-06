package com.tianji.remark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.remark.domain.po.LikedRecord;

/**
 * 点赞关系读取层，BaseMapper<LikedRecord> 的泛型告诉 MyBatis-Plus 使用哪个实体及表映射。
 * 继承的 selectList/selectCount 供 LikeCacheInitializer 分批恢复、LikeReconcileTask 抽样对账。
 * 当前没有自定义 Mapper XML；LikeChangePersistenceService 直接用 JdbcTemplate 批量 INSERT/DELETE。
 * 本接口只访问本地关系表，不负责跨服务查询业务内容或用户展示信息。
 */
public interface LikedRecordMapper extends BaseMapper<LikedRecord> {
}
