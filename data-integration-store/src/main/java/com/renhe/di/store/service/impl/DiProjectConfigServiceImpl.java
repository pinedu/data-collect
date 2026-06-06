package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.core.util.CryptoUtil;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.mapper.DiProjectConfigMapper;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 项目配置服务实现
 * 密码字段自动加解密
 */
@Slf4j
@Service
public class DiProjectConfigServiceImpl extends ServiceImpl<DiProjectConfigMapper, DiProjectConfig> implements DiProjectConfigService {

    @Override
    public List<DiProjectConfig> getAllActiveQxbProjects() {
        List<DiProjectConfig> list = baseMapper.selectAllActiveQxbProjects();
        // 解密密码
        for (DiProjectConfig config : list) {
            decryptPassword(config);
        }
        return list;
    }

    @Override
    public DiProjectConfig getBySourceProjectNum(String sourceProjectNum) {
        DiProjectConfig config = baseMapper.selectBySourceProjectNum(sourceProjectNum);
        if (config != null) {
            decryptPassword(config);
        }
        return config;
    }

    @Override
    public boolean save(DiProjectConfig entity) {
        encryptPassword(entity);
        return super.save(entity);
    }

    @Override
    public boolean updateById(DiProjectConfig entity) {
        // 只有当密码是明文时才加密
        if (entity.getPassword() != null && !CryptoUtil.isEncrypted(entity.getPassword())) {
            encryptPassword(entity);
        }
        return super.updateById(entity);
    }

    @Override
    public boolean saveOrUpdate(DiProjectConfig entity) {
        encryptPassword(entity);
        return super.saveOrUpdate(entity);
    }

    /**
     * 加密密码字段
     */
    private void encryptPassword(DiProjectConfig config) {
        if (config.getPassword() != null && !config.getPassword().isEmpty()
                && !CryptoUtil.isEncrypted(config.getPassword())) {
            config.setPassword(CryptoUtil.encrypt(config.getPassword()));
        }
    }

    /**
     * 解密密码字段
     */
    private void decryptPassword(DiProjectConfig config) {
//        if (config.getPassword() != null && !config.getPassword().isEmpty()
//                && CryptoUtil.isEncrypted(config.getPassword())) {
//            config.setPassword(CryptoUtil.decrypt(config.getPassword()));
//        }
    }
}
