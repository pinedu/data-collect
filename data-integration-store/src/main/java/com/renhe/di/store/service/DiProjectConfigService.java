package com.renhe.di.store.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.DiProjectConfig;

import java.util.List;

/**
 * 项目配置服务
 */
public interface DiProjectConfigService extends IService<DiProjectConfig> {

    /**
     * 获取所有启用的黔薪保项目
     */
    List<DiProjectConfig> getAllActiveQxbProjects();

    /**
     * 根据源项目编号获取配置
     */
    DiProjectConfig getBySourceProjectNum(String sourceProjectNum);
}
