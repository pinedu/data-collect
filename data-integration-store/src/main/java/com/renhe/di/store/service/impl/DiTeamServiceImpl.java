package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiTeam;
import com.renhe.di.store.mapper.DiTeamMapper;
import com.renhe.di.store.service.DiTeamService;
import org.springframework.stereotype.Service;

/**
 * 班组信息服务实现
 */
@Service
public class DiTeamServiceImpl extends ServiceImpl<DiTeamMapper, DiTeam> implements DiTeamService {
}
