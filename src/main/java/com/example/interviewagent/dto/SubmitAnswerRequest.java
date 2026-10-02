package com.example.interviewagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SubmitAnswerRequest(@Positive long turnId,
                                  @NotBlank @Size(max = 10000) String answer) {
}
