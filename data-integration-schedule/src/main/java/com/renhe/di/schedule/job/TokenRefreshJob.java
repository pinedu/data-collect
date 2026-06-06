package com.renhe.di.schedule.job;

import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.dispatch.alarm.DingTalkAlarmService;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token续期定时任务
 * 每6小时执行一次，遍历所有启用项目验证并续期Token
 */
@Slf4j
@Component
public class TokenRefreshJob {

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private DingTalkAlarmService alarmService;

    /**
     * 每6小时执行一次Token续期
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void execute() {
        log.info("开始执行Token续期任务...");

        List<DiProjectConfig> projects = projectConfigService.getAllActiveQxbProjects();
        if (projects.isEmpty()) {
            log.warn("未找到需要续期的项目配置");
            return;
        }

        int successCount = 0;
        int failCount = 0;
        int noTokenCount = 0;

        for (DiProjectConfig project : projects) {
            String account = project.getAccount();
            String projectName = project.getProjectName();
            String sourceProjectNum = project.getSourceProjectNum();

            try {
                // 检查Token是否存在
                if (!tokenManager.hasToken(account)) {
                    log.warn("项目【{}】账号【{}】Token不存在，跳过续期", projectName, account);
                    noTokenCount++;
                    continue;
                }

                // 验证并续期
                boolean extended = tokenManager.validateAndExtend(account, project.getPassword());
                if (extended) {
                    log.info("项目【{}】账号【{}】Token续期成功", projectName, account);
                    successCount++;
                } else {
                    log.warn("项目【{}】账号【{}】Token续期失败", projectName, account);
                    failCount++;
                    alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                            "Token续期失败，请检查账号密码或重新扫码");
                }

            } catch (Exception e) {
                log.error("项目【{}】账号【{}】Token续期异常", projectName, account, e);
                failCount++;
                alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                        "Token续期异常：" + e.getMessage());
            }
        }

        log.info("Token续期任务完成：项目总数={}, 成功={}, 失败={}, 无Token={}",
                projects.size(), successCount, failCount, noTokenCount);

        // 如果有失败项目，发送汇总告警
        if (failCount > 0) {
            alarmService.sendSyncReport("Token续期", projects.size(), successCount,
                    projects.size(), successCount, failCount);
        }
    }
}
