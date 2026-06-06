package com.renhe.di.collect.collector;

import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 项目信息采集器（单条，非分页）
 */
@Slf4j
@Component
public class ProjectCollector implements PagedDataCollector<JSONObject> {

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Override
    public PageResult<JSONObject> collectPage(CollectContext ctx, int pageNum, int pageSize) {
        JSONObject data = apiClient.getProjectData(
                ctx.getSourceProjectNum(),
                ctx.getAccount(),
                ctx.getPassword()
        );

        if (data == null) {
            return PageResult.empty();
        }

        return PageResult.<JSONObject>builder()
                .list(Collections.singletonList(data))
                .total(1)
                .pageNum(1)
                .pageSize(1)
                .hasNext(false)
                .build();
    }

    public List<JSONObject> collectAll(CollectContext ctx) {
        PageResult<JSONObject> result = collectPage(ctx, 1, 1);
        return result.getList();
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
        return "PROJECT";
    }
}
