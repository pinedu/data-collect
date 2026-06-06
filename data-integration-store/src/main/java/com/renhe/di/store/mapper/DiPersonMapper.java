package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.DiPerson;
import org.apache.ibatis.annotations.Mapper;

/**
 * 人员信息Mapper
 */
@Mapper
public interface DiPersonMapper extends BaseMapper<DiPerson> {
}
