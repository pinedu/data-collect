package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiPayrollDetail;
import com.renhe.di.store.mapper.DiPayrollDetailMapper;
import com.renhe.di.store.service.DiPayrollDetailService;
import org.springframework.stereotype.Service;

/**
 * 工资明细服务实现
 */
@Service
public class DiPayrollDetailServiceImpl extends ServiceImpl<DiPayrollDetailMapper, DiPayrollDetail> implements DiPayrollDetailService {
}
