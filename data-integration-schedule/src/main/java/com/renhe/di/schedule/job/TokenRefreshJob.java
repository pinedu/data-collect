package com.renhe.di.schedule.job;

import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.dispatch.alarm.WechatAlarmService;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

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
    private WechatAlarmService alarmService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String TOKEN_PROBE_KEY = "token:probe:";
    private static final int MAX_PROBE_COUNT = 3;
    private static final int PROBE_EXPIRE_MINUTES = 70;

    /**
     * 每20分钟执行一次Token续期
     */
    @Scheduled(cron = "0 */20 * * * ?")
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
        int removedCount = 0;

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
                    // 续期成功，清除探针计数
                    clearProbeCount(account);
                } else {
                    // 续期失败，进行风控探针
                    int probeCount = incrementProbeCount(account);
                    log.warn("项目【{}】账号【{}】Token续期失败，探针次数={}/{}",
                            projectName, account, probeCount, MAX_PROBE_COUNT);

                    if (probeCount >= MAX_PROBE_COUNT) {
                        // 达到最大探针次数，移除Token
                        tokenManager.removeToken(account);
                        clearProbeCount(account);
                        removedCount++;
                        log.error("项目【{}】账号【{}】Token续期失败已达{}次，已移除Token",
                                projectName, account, MAX_PROBE_COUNT);
                        alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                                "Token续期连续失败" + MAX_PROBE_COUNT + "次，Token已移除，请重新扫码");
                    } else if (probeCount == MAX_PROBE_COUNT - 1) {
                        // 倒数第二次发告警提醒
                        alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                                "Token续期失败（探针 " + probeCount + "/" + MAX_PROBE_COUNT +
                                        "），下次失败将移除Token");
                    }
                    failCount++;
                }

            } catch (Exception e) {
                log.error("项目【{}】账号【{}】Token续期异常", projectName, account, e);
                // 异常也纳入探针计数
                int probeCount = incrementProbeCount(account);
                log.warn("项目【{}】账号【{}】Token续期异常，探针次数={}/{}",
                        projectName, account, probeCount, MAX_PROBE_COUNT);

                if (probeCount >= MAX_PROBE_COUNT) {
                    tokenManager.removeToken(account);
                    clearProbeCount(account);
                    removedCount++;
                    log.error("项目【{}】账号【{}】Token续期异常已达{}次，已移除Token",
                            projectName, account, MAX_PROBE_COUNT);
                    alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                            "Token续期连续异常" + MAX_PROBE_COUNT + "次，Token已移除，请重新扫码");
                } else if (probeCount == MAX_PROBE_COUNT - 1) {
                    alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                            "Token续期异常（探针 " + probeCount + "/" + MAX_PROBE_COUNT +
                                    "），下次失败将移除Token");
                }
                failCount++;
            }
        }

        log.info("Token续期任务完成：项目总数={}, 成功={}, 失败={}, 无Token={}, 已移除={}",
                projects.size(), successCount, failCount, noTokenCount, removedCount);

        // 如果有失败项目，发送汇总告警
        if (failCount > 0) {
            alarmService.sendSyncReport("Token续期", projects.size(), successCount,
                    projects.size(), successCount, failCount);
        }
    }

    /**
     * 增加探针计数，返回当前计数
     */
    private int incrementProbeCount(String account) {
        String key = TOKEN_PROBE_KEY + account;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 首次设置，70分钟后过期（覆盖3次20分钟周期+缓冲）
            stringRedisTemplate.expire(key, PROBE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        }
        return count != null ? count.intValue() : 1;
    }

    /**
     * 清除探针计数
     */
    private void clearProbeCount(String account) {
        String key = TOKEN_PROBE_KEY + account;
        stringRedisTemplate.delete(key);
    }
}
