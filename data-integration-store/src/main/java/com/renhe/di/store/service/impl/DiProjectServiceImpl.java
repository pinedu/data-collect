package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.mapper.DiProjectMapper;
import com.renhe.di.store.service.DiProjectService;
import org.springframework.stereotype.Service;

/**
 * 项目信息服务实现
 */
@Service
public class DiProjectServiceImpl extends ServiceImpl<DiProjectMapper, DiProject> implements DiProjectService {
}
