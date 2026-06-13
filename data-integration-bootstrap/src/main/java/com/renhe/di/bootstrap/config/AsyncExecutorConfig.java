package com.renhe.di.bootstrap.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务线程池配置
 * 使用虚拟线程（Virtual Thread）执行 I/O 密集型批量同步任务
 */
@Slf4j
@Configuration
public class AsyncExecutorConfig {

    private ExecutorService executor;

    /**
     * 共享虚拟线程执行器，统一生命周期管理
     * 虚拟线程非常适合大量 I/O 密集型操作（HTTP 调用、数据库写入），
     * 不会阻塞 OS 平台线程，避免传统线程池的上下文切换开销
     */
    @Bean
    public ExecutorService syncTaskExecutor() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("虚拟线程同步执行器已初始化");
        return executor;
    }

    /**
     * 优雅关闭：先 shutdown() 拒绝新任务，再 shutdownNow() 中断正在运行的线程。
     * <p>
     * 关键场景：Pipeline 中的虚拟线程可能正在 recoverFromAntiCrawler 的长 sleep 中，
     * shutdown() 不会中断它们，必须 shutdownNow() 才能触发 InterruptedException 使其退出。
     */
    @PreDestroy
    public void shutdown() {
        if (executor == null) return;
        log.info("正在关闭虚拟线程同步执行器...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.info("仍有任务运行中，强制中断");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("虚拟线程同步执行器已关闭");
    }
}
