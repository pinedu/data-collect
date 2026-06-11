package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiProjectWarningIndicators;
import com.renhe.di.store.mapper.DiProjectWarningIndicatorsMapper;
import com.renhe.di.store.service.DiProjectWarningIndicatorsService;
import org.springframework.stereotype.Service;

/**
 * 项目6个百分百预警指标服务实现
 */
@Service
public class DiProjectWarningIndicatorsServiceImpl extends ServiceImpl<DiProjectWarningIndicatorsMapper, DiProjectWarningIndicators> implements DiProjectWarningIndicatorsService {
}
