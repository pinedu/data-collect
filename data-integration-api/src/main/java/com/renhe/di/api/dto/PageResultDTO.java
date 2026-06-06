package com.renhe.di.api.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果DTO
 */
@Data
@Builder
public class PageResultDTO<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Builder.Default
    private List<T> list = Collections.emptyList();

    @Builder.Default
    private int total = 0;

    @Builder.Default
    private int pageNum = 1;

    @Builder.Default
    private int pageSize = 10;

    @Builder.Default
    private boolean hasNext = false;

    public static <T> PageResultDTO<T> empty() {
        return PageResultDTO.<T>builder().build();
    }

    public static <T> PageResultDTO<T> of(List<T> list, int total, int pageNum, int pageSize) {
        boolean hasNext = pageNum * pageSize < total;
        return PageResultDTO.<T>builder()
                .list(list != null ? list : Collections.emptyList())
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .hasNext(hasNext)
                .build();
    }
}
