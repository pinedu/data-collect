package com.renhe.di.clean.cleaner;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.converter.ExtDataExtractor;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.DataCleaner;
import com.renhe.di.store.entity.DiPerson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 人员数据清洗器
 */
@Slf4j
@Component
public class PersonCleaner implements DataCleaner<JSONObject, DiPerson> {

    @Autowired
    private ExtDataExtractor extDataExtractor;

    @Override
    public DiPerson clean(JSONObject rawData, CleanContext ctx) {
        if (rawData == null) {
            return null;
        }

        DiPerson person = new DiPerson();
        person.setSourceProjectNum(ctx.getSourceProjectNum());
        person.setPersonId(rawData.getStr("id"));
        person.setPersonName(rawData.getStr("name"));
        person.setIdCardNo(rawData.getStr("idCardNo"));
        person.setPhone(rawData.getStr("phone"));
        person.setTeamId(rawData.getStr("teamId"));
        person.setTeamName(rawData.getStr("teamName"));
        person.setWorkType(rawData.getStr("workType"));
        person.setJobStatus(rawData.getInt("state", 1) == 1 ? "在职" : "离职");
        person.setFaceUrl(rawData.getStr("faceUrl"));
        person.setBankCardNo(rawData.getStr("bankCardNo"));
        person.setBankName(rawData.getStr("bank"));

        String beginDate = rawData.getStr("beginDate");
        if (beginDate != null && !beginDate.isEmpty()) {
            try {
                person.setRegisterTime(LocalDate.parse(beginDate, DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            } catch (Exception e) {
                log.warn("入职时间解析失败: {}", beginDate);
            }
        }

        String endDate = rawData.getStr("endDate");
        if (endDate != null && !endDate.isEmpty()) {
            try {
                person.setQuitTime(LocalDate.parse(endDate, DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            } catch (Exception e) {
                log.warn("离职时间解析失败: {}", endDate);
            }
        }

        person.setExtData(extDataExtractor.extractExtData(rawData));
        person.setDataVersion(1);
        person.setSyncType(ctx.getDataType());
        person.setSyncTime(LocalDateTime.now());

        return person;
    }

    @Override
    public boolean supports(String dataType) {
        return "PERSON".equals(dataType);
    }
}
