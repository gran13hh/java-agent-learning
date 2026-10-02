package com.example.interviewagent.dto;

import com.example.interviewagent.domain.Difficulty;
import com.example.interviewagent.domain.Topic;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 输入 DTO 不接受 id、创建时间；这些字段由服务端/数据库决定。 */
public record CreateQuestionRequest(
        @NotBlank(message = "题目不能为空")
        @Size(max = 200, message = "题目不能超过 200 个字符") String title,
        @NotNull(message = "主题不能为空") Topic topic,
        @NotNull(message = "难度不能为空") Difficulty difficulty,
        @NotBlank(message = "参考答案不能为空")
        @Size(max = 10000, message = "参考答案不能超过 10000 个字符") String referenceAnswer) {
}
