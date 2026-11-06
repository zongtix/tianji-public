package com.tianji.api.mcp;

import com.tianji.api.dto.course.CourseBaseInfoDTO;

import java.util.List;

/** Public course display fields only. IDs cross the protocol as decimal strings. */
public final class CourseDetailContract {
    public static final String SERVER_ID = "course-catalog";
    public static final String TOOL_NAME = "getCourseDetail";
    public static final int MAX_BYTES = 262144;
    public static final int MAX_CATALOGUE_ITEMS = 2000;
    public static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"courseId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},
             "required":["courseId"],"additionalProperties":false}
            """;

    private CourseDetailContract() { }

    public static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid course ID");
        return Long.parseLong(value);
    }

    public record Result(int schemaVersion, String status, Data data) {
        public static Result notFound() { return new Result(1, "NOT_FOUND", null); }
        public static Result found(CourseBaseInfoDTO dto) { return new Result(1, "FOUND", Data.from(dto)); }
    }

    public record Data(String id, String name, Integer price, Boolean free, Integer validDuration,
                       String usePeople, String detail, List<String> catalogue) {
        static Data from(CourseBaseInfoDTO dto) {
            return new Data(dto.getId().toString(), dto.getName(), dto.getPrice(), dto.getFree(),
                    dto.getValidDuration(), dto.getUsePeople(), dto.getDetail(), dto.getCatalogue());
        }

        public CourseBaseInfoDTO toDto() {
            var dto = new CourseBaseInfoDTO();
            dto.setId(parseId(id)); dto.setName(name); dto.setPrice(price); dto.setFree(free);
            dto.setValidDuration(validDuration); dto.setUsePeople(usePeople); dto.setDetail(detail);
            dto.setCatalogue(catalogue);
            return dto;
        }
    }
}
