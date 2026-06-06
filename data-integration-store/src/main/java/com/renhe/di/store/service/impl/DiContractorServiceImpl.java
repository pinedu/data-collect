package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiContractor;
import com.renhe.di.store.mapper.DiContractorMapper;
import com.renhe.di.store.service.DiContractorService;
import org.springframework.stereotype.Service;

/**
 * 参建单位服务实现
 */
@Service
public class DiContractorServiceImpl extends ServiceImpl<DiContractorMapper, DiContractor> implements DiContractorService {
}
