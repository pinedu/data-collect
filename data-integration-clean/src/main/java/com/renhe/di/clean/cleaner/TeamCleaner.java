package com.renhe.di.clean.cleaner;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.converter.ExtDataExtractor;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.DataCleaner;
import com.renhe.di.store.entity.DiTeam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 班组数据清洗器
 */
@Slf4j
@Component
public class TeamCleaner implements DataCleaner<JSONObject, DiTeam> {

    @Autowired
    private ExtDataExtractor extDataExtractor;

    @Override
    public DiTeam clean(JSONObject rawData, CleanContext ctx) {
        if (rawData == null) {
            return null;
        }

        DiTeam team = new DiTeam();
        team.setSourceProjectNum(ctx.getSourceProjectNum());
        team.setTeamId(rawData.getStr("id"));
        team.setTeamName(rawData.getStr("teamName"));
        team.setContractorId(rawData.getStr("entId"));
        team.setLeaderName(rawData.getStr("personName"));
        team.setLeaderIdcard(rawData.getStr("idCard"));
        team.setLeaderPhone(rawData.getStr("phone"));
        team.setWorkType(rawData.getStr("workType"));
        team.setTeamStatus(rawData.getInt("state", 1) == 1 ? "进场" : "退场");

        String beginDate = rawData.getStr("beginDate");
        if (beginDate != null && !beginDate.isEmpty()) {
            try {
                team.setApproachDate(LocalDate.parse(beginDate.substring(0, 10)));
            } catch (Exception e) {
                log.warn("进场日期解析失败: {}", beginDate);
            }
        }

        String endDate = rawData.getStr("endDate");
        if (endDate != null && !endDate.isEmpty()) {
            try {
                team.setDepartureDate(LocalDate.parse(endDate.substring(0, 10)));
            } catch (Exception e) {
                log.warn("退场日期解析失败: {}", endDate);
            }
        }

        team.setExtData(extDataExtractor.extractExtData(rawData));
        team.setDataVersion(1);
        team.setSyncType(ctx.getDataType());
        team.setSyncTime(LocalDateTime.now());

        return team;
    }

    @Override
    public boolean supports(String dataType) {
        return "TEAM".equals(dataType);
    }
}
