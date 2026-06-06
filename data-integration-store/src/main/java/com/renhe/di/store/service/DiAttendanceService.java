package com.renhe.di.store.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.DiAttendance;

import java.time.LocalDateTime;

/**
 * 考勤记录服务
 */
public interface DiAttendanceService extends IService<DiAttendance> {

    /**
     * 获取项目最后一条考勤记录时间
     */
    LocalDateTime getLastAttendanceTime(String projectNum);
}
