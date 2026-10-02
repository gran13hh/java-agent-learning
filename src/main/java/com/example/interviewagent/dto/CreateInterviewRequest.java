package com.example.interviewagent.dto;

import com.example.interviewagent.domain.Topic;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateInterviewRequest(Topic topic,
        @NotNull @Min(1) @Max(10) Integer questionCount) {
}
