package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiPayroll;
import com.renhe.di.store.mapper.DiPayrollMapper;
import com.renhe.di.store.service.DiPayrollService;
import org.springframework.stereotype.Service;

/**
 * 工资主表服务实现
 */
@Service
public class DiPayrollServiceImpl extends ServiceImpl<DiPayrollMapper, DiPayroll> implements DiPayrollService {
}
