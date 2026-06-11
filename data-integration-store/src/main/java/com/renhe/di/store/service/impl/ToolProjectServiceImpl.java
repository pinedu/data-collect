package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.ToolProject;
import com.renhe.di.store.mapper.ToolProjectMapper;
import com.renhe.di.store.service.ToolProjectService;
import org.springframework.stereotype.Service;

/**
 * 数据报告-项目信息服务实现
 */
@Service
public class ToolProjectServiceImpl extends ServiceImpl<ToolProjectMapper, ToolProject> implements ToolProjectService {
}
