package com.renhe.di.collect.collector;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 人员数据采集器
 */
@Slf4j
@Component
public class PersonCollector extends AbstractPagedCollector<JSONObject> {

    @Autowired
    private ThirdPartyApiClient apiClient;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 人员getPersonPage全局调用计数器（跨所有调用路径：PhaseA探针/末页/PhaseC分批采集） */
    public static final AtomicInteger API_CALL_COUNTER = new AtomicInteger(0);

    @Override
    public PageResult<JSONObject> collectPage(CollectContext ctx, int pageNum, int pageSize) {
        String beginDate = null;
        String endDate = null;
        if (ctx.getBeginTime() != null) {
            beginDate = ctx.getBeginTime().format(FORMATTER);
        }
        if (ctx.getEndTime() != null) {
            endDate = ctx.getEndTime().format(FORMATTER);
        }

        int callSeq = API_CALL_COUNTER.incrementAndGet();
        log.info("[PERSON API #{}] page={}, pageSize={}, project={}, beginDate={}, endDate={}",
                callSeq, pageNum, pageSize, ctx.getSourceProjectNum(), beginDate, endDate);

        JSONObject data = apiClient.getPersonPage(ctx.getSourceProjectNum(), pageNum, pageSize,
                beginDate, endDate, ctx.getAccount(), ctx.getPassword()
        );

        if (data == null) {
            log.info("[PERSON API #{}] 返回 null（空响应），total=N/A", callSeq);
            return PageResult.empty();
        }

        JSONArray list = data.getJSONArray("list");
        int returnedRows = (list != null) ? list.size() : 0;
        Integer total = data.getInt("total");
        Integer respPageSize = data.getInt("pageSize");

        // 响应日志：total + 返回条数 + 日期范围（用于快速诊断 API 过滤是否生效）
        if (list != null && !list.isEmpty()) {
            String firstDate = list.getJSONObject(0).getStr("beginDate", "?");
            String lastDate = list.getJSONObject(list.size() - 1).getStr("beginDate", "?");
            log.info("[PERSON API #{}] total={}, returned={}, page={}, pageSize={}, dates=[{} ~ {}]",
                    callSeq, total, returnedRows, pageNum, respPageSize != null ? respPageSize : pageSize,
                    firstDate, lastDate);
        } else {
            log.info("[PERSON API #{}] total={}, returned={}, page={}, pageSize={}",
                    callSeq, total, returnedRows, pageNum, respPageSize != null ? respPageSize : pageSize);
        }

        List<JSONObject> resultList = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                resultList.add(list.getJSONObject(i));
            }
        }

        boolean hasNext = pageNum * (respPageSize != null ? respPageSize : pageSize) < (total != null ? total : 0);

        return PageResult.<JSONObject>builder()
                .list(resultList)
                .total(total != null ? total : 0)
                .pageNum(pageNum)
                .pageSize(respPageSize != null ? respPageSize : pageSize)
                .hasNext(hasNext)
                .build();
    }

    @Override
    public boolean supportTimeRange() {
        return true;
    }

    @Override
    public String getDataId(JSONObject data) {
        return data.getStr("id");
    }

    @Override
    public String getDataType() {
        return "PERSON";
    }

    /**
     * 内容级跳过：数据按 beginDate DESC 排列。
     * 若该页最老记录（末笔）仍在月末之后 → 尚未到达目标月份 → 跳过但继续取。
     */
    @Override
    protected boolean shouldSkipPage(List<JSONObject> pageData, CollectContext ctx) {
        if (ctx.getEndTime() == null || pageData == null || pageData.isEmpty()) {
            return false;
        }
        LocalDate monthEnd = ctx.getEndTime().toLocalDate();
        // DESC排序 → 最后一笔是该页最早（最老）的时间
        JSONObject lastRecord = pageData.get(pageData.size() - 1);
        String beginDateStr = lastRecord.getStr("beginDate");
        if (beginDateStr == null || beginDateStr.isEmpty()) {
            return false;
        }
        try {
            LocalDate oldestDate = LocalDate.parse(beginDateStr, FORMATTER);
            // 全页都在月末之后 → 还没到目标月份 → 跳过但继续
            return oldestDate.isAfter(monthEnd);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 内容级终止：数据按 beginDate DESC 排列。
     * 若该页最新记录（首笔）已在月初之前 → 已越过目标月份 → 终止。
     */
    @Override
    protected boolean isBeyondTimeRange(List<JSONObject> pageData, CollectContext ctx) {
        if (ctx.getBeginTime() == null || pageData == null || pageData.isEmpty()) {
            return false;
        }
        LocalDate monthStart = ctx.getBeginTime().toLocalDate();
        // DESC排序 → 第一笔是该页最新（最近）的时间
        JSONObject firstRecord = pageData.get(0);
        String beginDateStr = firstRecord.getStr("beginDate");
        if (beginDateStr == null || beginDateStr.isEmpty()) {
            return false;
        }
        try {
            LocalDate newestDate = LocalDate.parse(beginDateStr, FORMATTER);
            // 全页（甚至更新记录）都在月初之前 → 已越过目标月份 → 终止
            return newestDate.isBefore(monthStart);
        } catch (Exception e) {
            return false;
        }
    }
}
