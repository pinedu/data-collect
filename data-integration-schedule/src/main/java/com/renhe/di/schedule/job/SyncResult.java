package com.renhe.di.schedule.job;

import lombok.Builder;
import lombok.Data;

/**
 * 同步结果
 */
@Data
@Builder
public class SyncResult {

    @Builder.Default
    private int totalCount = 0;

    @Builder.Default
    private int successCount = 0;

    @Builder.Default
    private int failCount = 0;

    @Builder.Default
    private int skipCount = 0;

    @Builder.Default
    private int thirdPartyTotal = 0;

    /** 是否触发了风控/反爬虫机制 */
    @Builder.Default
    private boolean antiCrawlerTriggered = false;

    public static SyncResult empty() {
        return SyncResult.builder().build();
    }

    public static SyncResult of(int total, int success, int fail, int skip) {
        return SyncResult.builder()
                .totalCount(total)
                .successCount(success)
                .failCount(fail)
                .skipCount(skip)
                .build();
    }

    public static SyncResult of(int total, int success, int fail, int skip, int thirdPartyTotal) {
        return SyncResult.builder()
                .totalCount(total)
                .successCount(success)
                .failCount(fail)
                .skipCount(skip)
                .thirdPartyTotal(thirdPartyTotal)
                .build();
    }

    /** 创建风控触发结果 */
    public static SyncResult antiCrawler(int total, int success, int fail, int skip) {
        return SyncResult.builder()
                .totalCount(total)
                .successCount(success)
                .failCount(fail)
                .skipCount(skip)
                .antiCrawlerTriggered(true)
                .build();
    }
}
