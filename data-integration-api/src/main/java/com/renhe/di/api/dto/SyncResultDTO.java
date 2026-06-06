package com.renhe.di.api.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 同步结果DTO
 */
@Data
@Builder
public class SyncResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private int totalCount;
    private int successCount;
    private int failCount;
    private int skipCount;
    private String message;

    public static SyncResultDTO empty() {
        return SyncResultDTO.builder()
                .totalCount(0)
                .successCount(0)
                .failCount(0)
                .skipCount(0)
                .message("无数据")
                .build();
    }

    public static SyncResultDTO of(int total, int success, int fail, int skip) {
        return SyncResultDTO.builder()
                .totalCount(total)
                .successCount(success)
                .failCount(fail)
                .skipCount(skip)
                .message(String.format("总计%d，成功%d，失败%d，跳过%d", total, success, fail, skip))
                .build();
    }
}
