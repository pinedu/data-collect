package com.renhe.di.collect.collector;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 班组数据采集器
 */
@Slf4j
@Component
public class TeamCollector extends AbstractPagedCollector<JSONObject> {

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Override
    public PageResult<JSONObject> collectPage(CollectContext ctx, int pageNum, int pageSize) {
        JSONObject data = apiClient.getTeamPage(ctx.getSourceProjectNum(), pageNum, pageSize, ctx.getAccount(), ctx.getPassword());
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
        return false;
    }

    @Override
    public String getDataId(JSONObject data) {
        return data.getStr("id");
    }

    @Override
    public String getDataType() {
        return "TEAM";
    }
}
