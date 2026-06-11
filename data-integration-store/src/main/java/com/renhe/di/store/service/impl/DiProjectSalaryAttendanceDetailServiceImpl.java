package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiProjectSalaryAttendanceDetail;
import com.renhe.di.store.mapper.DiProjectSalaryAttendanceDetailMapper;
import com.renhe.di.store.service.DiProjectSalaryAttendanceDetailService;
import org.springframework.stereotype.Service;

/**
 * 项目工资考勤统计明细服务实现
 */
@Service
public class DiProjectSalaryAttendanceDetailServiceImpl
        extends ServiceImpl<DiProjectSalaryAttendanceDetailMapper, DiProjectSalaryAttendanceDetail>
        implements DiProjectSalaryAttendanceDetailService {
}
