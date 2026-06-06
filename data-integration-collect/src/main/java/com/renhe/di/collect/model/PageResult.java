package com.renhe.di.collect.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 采集分页结果
 */
@Data
@Builder
public class PageResult<T> {

    @Builder.Default
    private List<T> list = Collections.emptyList();

    @Builder.Default
    private int total = 0;

    @Builder.Default
    private int pageNum = 1;

    @Builder.Default
    private int pageSize = 100;

    @Builder.Default
    private boolean hasNext = false;

    public static <T> PageResult<T> empty() {
        return PageResult.<T>builder().build();
    }

    public static <T> PageResult<T> of(List<T> list, int total, int pageNum, int pageSize) {
        boolean hasNext = pageNum * pageSize < total;
        return PageResult.<T>builder()
                .list(list != null ? list : Collections.emptyList())
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .hasNext(hasNext)
                .build();
    }

    public static <T> PageResult<T> of(List<T> list, int total, int pageNum, int pageSize, boolean hasNext) {
        return PageResult.<T>builder()
                .list(list != null ? list : Collections.emptyList())
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .hasNext(hasNext)
                .build();
    }
}
