package com.renhe.di.clean.cleaner;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.converter.ExtDataExtractor;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.DataCleaner;
import com.renhe.di.store.entity.DiAttendance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 考勤数据清洗器
 */
@Slf4j
@Component
public class AttendanceCleaner implements DataCleaner<JSONObject, DiAttendance> {

    @Autowired
    private ExtDataExtractor extDataExtractor;

    @Override
    public DiAttendance clean(JSONObject rawData, CleanContext ctx) {
        if (rawData == null) {
            return null;
        }

        DiAttendance attendance = new DiAttendance();
        attendance.setSourceProjectNum(ctx.getSourceProjectNum());
        attendance.setAttendanceId(rawData.getStr("id"));
        attendance.setPersonId(rawData.getStr("personId"));
        attendance.setPersonName(rawData.getStr("personName"));
        attendance.setTeamId(rawData.getStr("teamId"));
        attendance.setTeamName(rawData.getStr("teamName"));
        attendance.setAttendanceDirection("in".equals(rawData.getStr("direction")) ? "进" : "出");
        attendance.setAttendanceWay("FACE".equals(rawData.getStr("type")) ? "人脸识别" : "身份证刷卡");
        attendance.setAttendanceAddress(rawData.getStr("deviceName"));
        attendance.setAttendanceUrl(rawData.getStr("photoUrl"));
        attendance.setJobStatus(rawData.getStr("personStateStr"));

        String clockingTime = rawData.getStr("clockingTime");
        if (StringUtils.hasLength(clockingTime)) {
            try {
                attendance.setAttendanceTime(LocalDateTime.parse(clockingTime,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (Exception e) {
                log.warn("考勤时间解析失败: {}", clockingTime);
            }
        }

        attendance.setExtData(extDataExtractor.extractExtData(rawData));
        attendance.setDataVersion(1);
        attendance.setSyncType(ctx.getDataType());
        attendance.setSyncTime(LocalDateTime.now());

        return attendance;
    }

    @Override
    public boolean supports(String dataType) {
        return "ATTENDANCE".equals(dataType);
    }
}
