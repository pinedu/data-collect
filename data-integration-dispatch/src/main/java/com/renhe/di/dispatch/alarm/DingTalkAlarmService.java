package com.renhe.di.dispatch.alarm;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉告警服务
 * 支持Webhook机器人推送告警消息
 */
@Slf4j
@Service
public class DingTalkAlarmService {

    @Value("${alarm.dingtalk.webhook:}")
    private String webhookUrl;

    @Value("${alarm.dingtalk.secret:}")
    private String secret;

    @Value("${alarm.dingtalk.enabled:false}")
    private boolean enabled;

    /**
     * 发送同步异常告警
     *
     * @param projectNum 项目编号
     * @param dataType   数据类型
     * @param errorMsg   错误信息
     */
    public void sendSyncAlarm(String projectNum, String dataType, String errorMsg) {
        if (!enabled) {
            log.warn("钉钉告警未启用");
            return;
        }

        String title = "数据同步异常告警";
        String content = String.format(
                "### 数据同步异常告警\n\n" +
                        "- **项目编号**：%s\n" +
                        "- **数据类型**：%s\n" +
                        "- **异常信息**：%s\n" +
                        "- **告警时间**：%s\n",
                projectNum, dataType, errorMsg, java.time.LocalDateTime.now()
        );

        sendMarkdown(title, content);
    }

    /**
     * 发送数据一致性告警
     */
    public void sendConsistencyAlarm(String projectNum, String dataType, int remoteCount, int localCount) {
        if (!enabled) {
            return;
        }

        String title = "数据一致性异常告警";
        String content = String.format(
                "### 数据一致性异常告警\n\n" +
                        "- **项目编号**：%s\n" +
                        "- **数据类型**：%s\n" +
                        "- **第三方平台**：%d条\n" +
                        "- **本地数据库**：%d条\n" +
                        "- **差异**：%d条\n" +
                        "- **告警时间**：%s\n",
                projectNum, dataType, remoteCount, localCount,
                Math.abs(remoteCount - localCount), java.time.LocalDateTime.now()
        );

        sendMarkdown(title, content);
    }

    /**
     * 发送同步汇总报告
     */
    public void sendSyncReport(String dataType, int totalProjects, int successProjects,
                                int totalCount, int successCount, int failCount) {
        if (!enabled) {
            return;
        }

        String title = "数据同步日报";
        String content = String.format(
                "### 数据同步日报 - %s\n\n" +
                        "- **同步类型**：%s\n" +
                        "- **项目总数**：%d\n" +
                        "- **成功项目**：%d\n" +
                        "- **数据总量**：%d\n" +
                        "- **成功数**：%d\n" +
                        "- **失败数**：%d\n" +
                        "- **成功率**：%.2f%%\n" +
                        "- **报告时间**：%s\n",
                dataType, dataType, totalProjects, successProjects,
                totalCount, successCount, failCount,
                totalCount > 0 ? (successCount * 100.0 / totalCount) : 0,
                java.time.LocalDateTime.now()
        );

        sendMarkdown(title, content);
    }

    /**
     * 发送Markdown消息
     */
    private void sendMarkdown(String title, String content) {
        try {
            JSONObject markdown = new JSONObject();
            markdown.set("title", title);
            markdown.set("text", content);

            JSONObject message = new JSONObject();
            message.set("msgtype", "markdown");
            message.set("markdown", markdown);

            String result = HttpRequest.post(webhookUrl)
                    .body(message.toString())
                    .timeout(10000)
                    .execute().body();

            log.info("钉钉告警发送结果: {}", result);
        } catch (Exception e) {
            log.error("钉钉告警发送失败", e);
        }
    }
}
