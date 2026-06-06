package com.renhe.di.clean.config;

import com.renhe.di.clean.cleaner.*;
import com.renhe.di.clean.pipeline.CleanPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 清洗流水线配置
 * 注册所有数据清洗器到流水线
 */
@Configuration
public class CleanPipelineConfig {

    @Autowired
    private ProjectCleaner projectCleaner;

    @Autowired
    private TeamCleaner teamCleaner;

    @Autowired
    private PersonCleaner personCleaner;

    @Autowired
    private PayrollCleaner payrollCleaner;

    @Autowired
    private PayrollDetailCleaner payrollDetailCleaner;

    @Autowired
    private AttendanceCleaner attendanceCleaner;

    @Bean
    public CleanPipeline cleanPipeline() {
        CleanPipeline pipeline = new CleanPipeline();
        pipeline.registerCleaner(projectCleaner);
        pipeline.registerCleaner(teamCleaner);
        pipeline.registerCleaner(personCleaner);
        pipeline.registerCleaner(payrollCleaner);
        pipeline.registerCleaner(payrollDetailCleaner);
        pipeline.registerCleaner(attendanceCleaner);
        return pipeline;
    }
}
