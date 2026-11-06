package com.tianji.learning.service.interaction.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CatalogueDTO;
import com.tianji.api.dto.course.CourseDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.interaction.InteractionQuestionMapper;
import com.tianji.learning.service.interaction.IInteractionQuestionService;
import com.tianji.learning.service.interaction.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion>
        implements IInteractionQuestionService {

    private final IInteractionReplyService replyService;

    private final UserClient userClient;

    private final CourseClient courseClient;

    private final SearchClient searchClient;

    private final CatalogueClient catalogueClient;

    private final CategoryCache categoryCache;

    @Override
    public void saveQuestion(QuestionFormDTO questionFormDTO) {
        Long userId = UserContext.getUser();
        InteractionQuestion interactionQuestion = BeanUtils.copyBean(questionFormDTO, InteractionQuestion.class);
        interactionQuestion.setUserId(userId);
        save(interactionQuestion);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        Long userId = UserContext.getUser();
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if (courseId==null&&sectionId==null){
            throw new BadRequestException("课程或章节信息有误！");
        }
        Page<InteractionQuestion> page = lambdaQuery()
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, userId)
                .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                .eq(sectionId != null, InteractionQuestion::getSectionId, sectionId)
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();

        //3.1、得到问题中的提问者id和最近一次回答的id
        for (InteractionQuestion record : records) {
            if (!record.getAnonymity()){
                userIds.add(record.getUserId());
            }
            answerIds.add(record.getLatestAnswerId());
        }

        //3.2、根据id查最后一次回答
        List<InteractionReply> replies = replyService.listByIds(answerIds);
        answerIds.remove(null);
        Map<Long,InteractionReply> replyMap = new HashMap<>(replies.size());
        if (CollUtils.isNotEmpty(answerIds)){
            for (InteractionReply reply : replies) {
                replyMap.put(reply.getId(),reply);
                if (!reply.getAnonymity()){
                    userIds.add(reply.getUserId());
                }
            }
        }
        //3.3、根据id查用户信息
        userIds.remove(null);
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userIds)){
            List<UserDTO> users= userClient.queryUserByIds(userIds);
            userMap = users.stream()
                    .collect(Collectors.toMap(UserDTO::getId, user -> user));
        }

        //4、封装VO
        List<QuestionVO> questionVOS = new ArrayList<>(records.size());
        for (InteractionQuestion record : records) {
            //4.1、将PO转为VO
            QuestionVO questionVO = BeanUtils.copyBean(record, QuestionVO.class);
            questionVOS.add(questionVO);
            //4.2、封装提问者信息
            if (!record.getAnonymity()){
                UserDTO userDTO = userMap.get(record.getUserId());
                if (userDTO != null) {
                    questionVO.setUserName(userDTO.getName());
                    questionVO.setUserIcon(userDTO.getIcon());
                }
            }

            //4.3、封装最近一次回答信息
            InteractionReply reply = replyMap.get(record.getLatestAnswerId());
            if (reply != null) {
                questionVO.setLatestReplyContent(reply.getContent());
                if (!reply.getAnonymity()) {
                    UserDTO user = userMap.get(reply.getUserId());
                    questionVO.setLatestReplyUser(user.getName());
                }

            }
        }

        return PageDTO.of(page, questionVOS);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        //1、根据id查数据
        InteractionQuestion question = getById(id);

        //2、数据校验
         if (question == null||question.getHidden()) {
             throw new BadRequestException("问题不存在");
         }
         //3、查询提问者信息
        UserDTO user = null;
        if (!question.getAnonymity()) {
            user = userClient.queryUserById(question.getUserId());
        }
        //4、封装VO
        QuestionVO questionVO = BeanUtils.copyBean(question, QuestionVO.class);
        if (user != null) {
            questionVO.setUserName(user.getName());
            questionVO.setUserIcon(user.getIcon());
        }
        return questionVO;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        //1、处理课程名称，得到课程id
        List<Long> courseIds = null;
        if (StringUtils.isNotBlank(query.getCourseName())){
            courseIds = searchClient.queryCoursesIdByName(query.getCourseName());
            if (CollUtils.isEmpty(courseIds)){
                return PageDTO.empty(0L,0L);
            }
        }
        //2、分页查询
        Integer status = query.getStatus();
        LocalDateTime beginTime = query.getBeginTime();
        LocalDateTime endTime = query.getEndTime();
        Page<InteractionQuestion> page = lambdaQuery().in(courseIds != null, InteractionQuestion::getCourseId, courseIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(beginTime != null, InteractionQuestion::getCreateTime, beginTime)
                .lt(endTime != null, InteractionQuestion::getCreateTime, endTime)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }

        //3、准备VO需要的数据：用户数据、课程数据、章节数据、分类数据
        Set<Long> userIds = new HashSet<>();
        Set<Long> cIds = new HashSet<>();
        Set<Long> cateIds = new HashSet<>();
        //3.1、获取records中的用户id、课程id、章节id、分类id
        for (InteractionQuestion record : records) {
            userIds.add(record.getUserId());
            cIds.add(record.getCourseId());
            cateIds.add(record.getChapterId());
            cateIds.add(record.getSectionId());
        }
        //3.2、根据用户id查询用户信息
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = new HashMap<>(users.size());
        if (CollUtils.isNotEmpty(users)){
            userMap = users.stream()
                    .collect(Collectors.toMap(UserDTO::getId, user -> user));
        }
        //3.3、根据课程id查询课程信息
        List<CourseSimpleInfoDTO> cInfos = courseClient.getSimpleInfoList(cIds);
        Map<Long, CourseSimpleInfoDTO> cInfoMap = new HashMap<>(cInfos.size());
        if (CollUtils.isNotEmpty(cInfos)){
            cInfoMap = cInfos.stream()
                    .collect(Collectors.toMap(CourseSimpleInfoDTO::getId, cInfo -> cInfo));
        }
        //3.4、根据章节id查询章节信息
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(cateIds);
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (CollUtils.isNotEmpty(catas)){
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId,CataSimpleInfoDTO::getName));
        }
        //4、封装VO
        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion record : records) {
            //4.1、将PO转为VO
            QuestionAdminVO vo = BeanUtils.copyBean(record, QuestionAdminVO.class);
            voList.add(vo);
            //4.2、封装提问者信息
            UserDTO user = userMap.get(record.getUserId());
            if (user != null) {
                vo.setUserName(user.getName());
            }
            //4.3、封装课程和分类信息
            CourseSimpleInfoDTO cInfo = cInfoMap.get(record.getCourseId());
            if (cInfo != null) {
                vo.setCourseName(cInfo.getName());
                vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds()));
            }
            //4.4、封装章、节信息
            vo.setChapterName(cataMap.getOrDefault(record.getChapterId(), ""));
            vo.setSectionName(cataMap.getOrDefault(record.getSectionId(), ""));
        }
        return PageDTO.of(page, voList);
    }

    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        // 1、根据id查询问题
        InteractionQuestion question = getById(id);
        if (question == null) {
            return null;
        }

        // 2、将问题基本信息封装到VO
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);

        // 3、查询并封装提问者信息
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }

        // 4、查询并封装课程、分类信息
        CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(question.getCourseId(), false, false);
        if (courseInfo != null) {
            vo.setCourseName(courseInfo.getName());
            vo.setCategoryName(categoryCache.getCategoryNames(courseInfo.getCategoryIds()));
        }

        // 5、查询并封装章、节信息
        List<CataSimpleInfoDTO> catalogues = catalogueClient.batchQueryCatalogue(
                List.of(question.getChapterId(), question.getSectionId()));
        Map<Long, String> catalogueMap = new HashMap<>();
        if (CollUtils.isNotEmpty(catalogues)) {
            catalogueMap = catalogues.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }
        vo.setChapterName(catalogueMap.getOrDefault(question.getChapterId(), ""));
        vo.setSectionName(catalogueMap.getOrDefault(question.getSectionId(), ""));
        return vo;
    }
}
