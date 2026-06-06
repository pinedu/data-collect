package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.store.mapper.DiAttendanceMapper;
import com.renhe.di.store.service.DiAttendanceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 考勤记录服务实现
 */
@Service
public class DiAttendanceServiceImpl extends ServiceImpl<DiAttendanceMapper, DiAttendance> implements DiAttendanceService {

    @Override
    public LocalDateTime getLastAttendanceTime(String projectNum) {
        return baseMapper.selectLastAttendanceTime(projectNum);
    }
}
