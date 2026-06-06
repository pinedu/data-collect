package com.renhe.di.bootstrap.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步任务线程池配置
 * 使用虚拟线程（Virtual Thread）执行 I/O 密集型批量同步任务
 */
@Slf4j
@Configuration
public class AsyncExecutorConfig {

    /**
     * 共享虚拟线程执行器，统一生命周期管理
     * 虚拟线程非常适合大量 I/O 密集型操作（HTTP 调用、数据库写入），
     * 不会阻塞 OS 平台线程，避免传统线程池的上下文切换开销
     */
    @Bean
    public ExecutorService syncTaskExecutor() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("虚拟线程同步执行器已初始化");
        return executor;
    }
}
