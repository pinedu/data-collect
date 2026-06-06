package com.renhe.di.collect.collector;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 考勤数据采集器
 */
@Slf4j
@Component
public class AttendanceCollector extends AbstractPagedCollector<JSONObject> {

    @Autowired
    private ThirdPartyApiClient apiClient;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResult<JSONObject> collectPage(CollectContext ctx, int pageNum, int pageSize) {
        String beginTime = ctx.getBeginTime() != null ? ctx.getBeginTime().format(FORMATTER) : "";
        String endTime = ctx.getEndTime() != null ? ctx.getEndTime().format(FORMATTER) : "";

        JSONObject data = apiClient.getAttendancePage(
                ctx.getSourceProjectNum(), pageNum, pageSize,
                beginTime, endTime,
                ctx.getAccount(), ctx.getPassword()
        );

        if (data == null) {
            return PageResult.empty();
        }

        Integer total = data.getInt("total");
        Integer respPageSize = data.getInt("pageSize");
        JSONArray list = data.getJSONArray("list");

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
        return "ATTENDANCE";
    }

    /**
     * 内容级跳过：数据按 clockingTime DESC 排列。
     * 若该页最老记录（末笔）仍在月末之后 → 尚未到达目标月份 → 跳过但继续取。
     */
    @Override
    protected boolean shouldSkipPage(List<JSONObject> pageData, CollectContext ctx) {
        if (ctx.getEndTime() == null || pageData == null || pageData.isEmpty()) {
            return false;
        }
        LocalDateTime monthEnd = ctx.getEndTime();
        // DESC排序 → 最后一笔是该页最早（最老）的时间
        JSONObject lastRecord = pageData.get(pageData.size() - 1);
        String clockingTime = lastRecord.getStr("clockingTime");
        if (clockingTime == null || clockingTime.isEmpty()) {
            return false;
        }
        try {
            LocalDateTime oldestTime = LocalDateTime.parse(clockingTime, FORMATTER);
            return oldestTime.isAfter(monthEnd);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 内容级终止：数据按 clockingTime DESC 排列。
     * 若该页最新记录（首笔）已在月初之前 → 已越过目标月份 → 终止。
     */
    @Override
    protected boolean isBeyondTimeRange(List<JSONObject> pageData, CollectContext ctx) {
        if (ctx.getBeginTime() == null || pageData == null || pageData.isEmpty()) {
            return false;
        }
        LocalDateTime monthStart = ctx.getBeginTime();
        // DESC排序 → 第一笔是该页最新（最近）的时间
        JSONObject firstRecord = pageData.get(0);
        String clockingTime = firstRecord.getStr("clockingTime");
        if (clockingTime == null || clockingTime.isEmpty()) {
            return false;
        }
        try {
            LocalDateTime newestTime = LocalDateTime.parse(clockingTime, FORMATTER);
            return newestTime.isBefore(monthStart);
        } catch (Exception e) {
            return false;
        }
    }
}
