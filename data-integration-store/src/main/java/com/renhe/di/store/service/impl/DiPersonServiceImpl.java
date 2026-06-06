package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiPerson;
import com.renhe.di.store.mapper.DiPersonMapper;
import com.renhe.di.store.service.DiPersonService;
import org.springframework.stereotype.Service;

/**
 * 人员信息服务实现
 */
@Service
public class DiPersonServiceImpl extends ServiceImpl<DiPersonMapper, DiPerson> implements DiPersonService {
}
