package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.DiProjectConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目配置Mapper
 */
@Mapper
public interface DiProjectConfigMapper extends BaseMapper<DiProjectConfig> {

    /**
     * 查询所有启用的黔薪保项目配置
     */
    @Select("SELECT * FROM di_project_config WHERE plat_num = 1 AND status = 1 AND deleted = 0")
    List<DiProjectConfig> selectAllActiveQxbProjects();

    /**
     * 根据源项目编号查询配置
     */
    @Select("SELECT * FROM di_project_config WHERE source_project_num = #{sourceProjectNum} AND deleted = 0 LIMIT 1")
    DiProjectConfig selectBySourceProjectNum(@Param("sourceProjectNum") String sourceProjectNum);
}
