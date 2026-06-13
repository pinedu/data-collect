package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.dispatch.alarm.WechatAlarmService;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Token续期定时任务
 * 每20分钟执行一次，遍历所有启用项目通过实际API调用验证并续期Token
 * <p>
 * Token移除策略：仅在API明确返回 code=401, msg=登录过期 时才移除Token
 * 其他错误（风控封号、网络异常等）视为临时性问题，保留Token等待自动恢复
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
        int expiredCount = 0;
        int tempErrorCount = 0;
        int noTokenCount = 0;
        List<String> successProjects = new ArrayList<>();
        List<String> expiredProjects = new ArrayList<>();
        List<String> tempErrorProjects = new ArrayList<>();
        List<String> failedNoRecordProjects = new ArrayList<>();

        for (DiProjectConfig project : projects) {
            String account = project.getAccount();
            String projectName = project.getProjectName();
            String sourceProjectNum = project.getSourceProjectNum();

            try {
                // 检查Token是否存在
                if (!tokenManager.hasToken(account)) {
                    log.warn("项目【{}】账号【{}】Token不存在，跳过续期", projectName, account);
                    failedNoRecordProjects.add(projectName);
                    noTokenCount++;
                    continue;
                }

                // 实际调用API验证Token有效性
                TokenManager.ValidateResult result = tokenManager.validateAndExtend(
                        account, project.getPassword(), sourceProjectNum);

                switch (result) {
                    case VALID:
                        log.info("项目【{}】账号【{}】Token验证通过，已续期", projectName, account);
                        successProjects.add(projectName);
                        successCount++;
                        break;

                    case EXPIRED:
                        // 明确的401登录过期，移除Token并告警
                        tokenManager.removeToken(account);
                        expiredCount++;
                        expiredProjects.add(projectName);
                        log.error("项目【{}】账号【{}】Token已过期（401登录过期），已移除Token", projectName, account);
                        alarmService.sendSyncAlarm(sourceProjectNum, "TOKEN_REFRESH",
                                "Token已过期（401登录过期），Token已移除，请重新扫码");
                        break;

                    case TEMP_ERROR:
                        // 临时错误（风控封号/网络异常等），保留Token，不发告警
                        tempErrorCount++;
                        tempErrorProjects.add(projectName);
                        log.warn("项目【{}】账号【{}】Token验证遇到临时错误，保留Token等待自动恢复", projectName, account);
                        break;
                }

            } catch (Exception e) {
                // 未预期的异常，视为临时错误，保留Token
                tempErrorCount++;
                tempErrorProjects.add(projectName);
                log.error("项目【{}】账号【{}】Token续期异常，保留Token等待恢复", projectName, account, e);
            }
        }

        log.info("Token续期任务完成：项目总数={}, 成功={}, 已过期移除={}, 临时错误={}, 无Token={}",
                projects.size(), successCount, expiredCount, tempErrorCount, noTokenCount);

        // 如果有过期或临时错误，发送汇总报告
        int failCount = expiredCount + tempErrorCount;
        if (failCount > 0) {
            // 生成报告ID（当天日期），将项目名列表存入Redis供报告页面查询
            String reportId = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            JSONObject reportData = new JSONObject();
            reportData.set("success", successProjects);
            reportData.set("expired", expiredProjects);
            reportData.set("tempError", tempErrorProjects);
            reportData.set("failedNoRecord", failedNoRecordProjects);
            reportData.set("time", LocalDateTime.now().toString());
            stringRedisTemplate.opsForValue().set("token:report:" + reportId, reportData.toString(), 7, TimeUnit.DAYS);

            alarmService.sendSyncReport("Token续期", reportId, projects.size(), successCount,
                    projects.size(), successCount, failCount);
        }
    }
}
