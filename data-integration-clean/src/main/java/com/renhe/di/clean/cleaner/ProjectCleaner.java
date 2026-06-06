package com.renhe.di.clean.cleaner;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.converter.ExtDataExtractor;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.DataCleaner;
import com.renhe.di.store.entity.DiProject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 项目数据清洗器
 */
@Slf4j
@Component
public class ProjectCleaner implements DataCleaner<JSONObject, DiProject> {

    @Autowired
    private ExtDataExtractor extDataExtractor;

    @Override
    public DiProject clean(JSONObject rawData, CleanContext ctx) {
        if (rawData == null) {
            return null;
        }

        DiProject project = new DiProject();
        project.setId(rawData.getStr("id"));
        project.setSourceProjectNum(ctx.getSourceProjectNum());
        project.setProjectNum(rawData.getStr("id"));
        project.setProjectName(rawData.getStr("fullName"));
        project.setRecordNumber(rawData.getStr("filingNo"));
        project.setAreaCode(rawData.getStr("areaCode") + "00");
        project.setProjectStatus(rawData.getStr("projectState"));

        BigDecimal lon = rawData.getBigDecimal("longitude");
        BigDecimal lat = rawData.getBigDecimal("latitude");
        project.setLon(lon);
        project.setLat(lat);

        String actualBeginDate = rawData.getStr("actualBeginDate");
        if (actualBeginDate != null && !actualBeginDate.isEmpty()) {
            try {
                project.setCommencementDate(LocalDate.parse(actualBeginDate.substring(0, 10)));
            } catch (Exception e) {
                log.warn("开工日期解析失败: {}", actualBeginDate);
            }
        }

        project.setExtData(extDataExtractor.extractExtData(rawData));
        project.setDataVersion(1);
        project.setSyncType(ctx.getDataType());
        project.setSyncTime(LocalDateTime.now());

        return project;
    }

    @Override
    public boolean supports(String dataType) {
        return "PROJECT".equals(dataType);
    }
}
