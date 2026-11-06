package com.tianji.aigc.knowledge.material;

import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagRequestGuard;
import org.springframework.ai.vectorstore.filter.Filter;

/** 课程讲义检索入口；内部持有独立索引，不注册全局 VectorStore Bean。 */
public class CourseMaterialSearch {
    public static final String INDEX = "tj_aigc_course_materials_v1_1024";

    private final KnowledgeRetrievalPipeline pipeline;

    public CourseMaterialSearch(KnowledgeRetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public KnowledgeRetrievalPipeline.Result search(String question, Filter.Expression scope,
            RagRequestGuard guard) {
        return pipeline.retrieve(question, scope, guard);
    }
}
