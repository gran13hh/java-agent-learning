package com.example.interviewagent.dto;

import java.util.List;

/** 泛型让同一分页结构可以承载不同类型；page 从 1 开始，total 是记录总数。 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
