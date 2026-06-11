package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiProjectMonthlySalaryAttendanceStats;
import com.renhe.di.store.mapper.DiProjectMonthlySalaryAttendanceStatsMapper;
import com.renhe.di.store.service.DiProjectMonthlySalaryAttendanceStatsService;
import org.springframework.stereotype.Service;

/**
 * 项目月份工资考勤统计服务实现
 */
@Service
public class DiProjectMonthlySalaryAttendanceStatsServiceImpl
        extends ServiceImpl<DiProjectMonthlySalaryAttendanceStatsMapper, DiProjectMonthlySalaryAttendanceStats>
        implements DiProjectMonthlySalaryAttendanceStatsService {
}
