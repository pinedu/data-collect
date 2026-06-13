package com.renhe.di.dispatch.alarm;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PostConstruct;

/**
 * 企业微信机器人告警服务
 * 支持Webhook机器人推送Markdown告警消息，带速率限制和队列重试
 */
@Slf4j
@Service
public class WechatAlarmService {

    @Value("${alarm.wechat.webhook:}")
    private String webhookUrl;

    @Value("${alarm.wechat.key:}")
    private String key;

    @Value("${alarm.wechat.enabled:false}")
    private boolean enabled;

    @Value("${alarm.wechat.keyword:筑采助手}")
    private String keyword;

    @Value("${alarm.wechat.max-per-minute:20}")
    private int maxPerMinute;

    @Value("${alarm.wechat.max-per-day:2000}")
    private int maxPerDay;


    @Value("${alarm.report.base-url}")
    private String reportBaseUrl;

    private static final ReentrantLock RATE_LOCK = new ReentrantLock();
    private static long windowMinute = -1;
    private static int minuteCount = 0;
    private static LocalDate currentDay = LocalDate.now();
    private static int dayCount = 0;
    private static final LinkedBlockingDeque<Msg> QUEUE = new LinkedBlockingDeque<>();
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);

    private static class Msg {
        final String title;
        final String content;
        Msg(String t, String c) { this.title = t; this.content = c; }
    }

    @PostConstruct
    public void startWorker() {
        if (WORKER_STARTED.compareAndSet(false, true)) {
            Thread t = new Thread(this::workerLoop, "wechat-notifier-worker");
            t.setDaemon(true);
            t.start();
        }
    }

    private void workerLoop() {
        while (true) {
            try {
                refreshWindow();
                if (isDayLimited()) {
                    long msToNextDay = TimeUnit.DAYS.toMillis(1) - (System.currentTimeMillis() % TimeUnit.DAYS.toMillis(1));
                    TimeUnit.MILLISECONDS.sleep(Math.min(msToNextDay, 60_000));
                    continue;
                }
                if (isMinuteLimited()) {
                    long msToNextMinute = 60_000 - (System.currentTimeMillis() % 60_000);
                    TimeUnit.MILLISECONDS.sleep(msToNextMinute);
                    continue;
                }
                Msg msg = QUEUE.poll(1, TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }
                boolean sent = doSend(msg);
                if (!sent) {
                    QUEUE.addFirst(msg);
                    TimeUnit.SECONDS.sleep(5);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("企业微信发送工作线程异常: {}", e.getMessage(), e);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void refreshWindow() {
        long nowMin = System.currentTimeMillis() / 60000;
        RATE_LOCK.lock();
        try {
            if (nowMin != windowMinute) {
                windowMinute = nowMin;
                minuteCount = 0;
            }
            LocalDate today = LocalDate.now();
            if (!today.equals(currentDay)) {
                currentDay = today;
                dayCount = 0;
            }
        } finally {
            RATE_LOCK.unlock();
        }
    }

    private boolean isDayLimited() {
        RATE_LOCK.lock();
        try {
            return dayCount >= maxPerDay;
        } finally {
            RATE_LOCK.unlock();
        }
    }

    private boolean isMinuteLimited() {
        RATE_LOCK.lock();
        try {
            return minuteCount >= maxPerMinute;
        } finally {
            RATE_LOCK.unlock();
        }
    }

    private boolean doSend(Msg msg) {
        try {
            JSONObject message = new JSONObject();
            message.set("msgtype", "markdown");
            JSONObject markdown = new JSONObject();
            markdown.set("content", "## " + msg.title + "\n\n" + msg.content);
            message.set("markdown", markdown);

            String fullUrl = webhookUrl;
            if (StringUtils.hasLength(key)) {
                fullUrl = webhookUrl + "?key=" + key;
            }

            cn.hutool.http.HttpResponse httpResponse = HttpRequest.post(fullUrl)
                    .header("Content-Type", "application/json")
                    .body(message.toString())
                    .timeout(10000)
                    .execute();
            String response = httpResponse.body();
            int httpStatus = httpResponse.getStatus();

            if (httpStatus != 200 || response == null || response.trim().isEmpty()) {
                log.warn("企业微信发送HTTP异常: status={}", httpStatus);
                return false;
            }
            String trimmedResponse = response.trim();
            if (!trimmedResponse.startsWith("{") && !trimmedResponse.startsWith("[")) {
                log.warn("企业微信返回非JSON: {}", trimmedResponse);
                return false;
            }
            JSONObject result = JSONUtil.parseObj(response);
            int errcode = result.getInt("errcode", -1);
            if (errcode != 0) {
                log.warn("企业微信返回错误: errcode={}, errmsg={}", errcode, result.getStr("errmsg"));
                return false;
            }

            RATE_LOCK.lock();
            try {
                minuteCount++;
                dayCount++;
            } finally {
                RATE_LOCK.unlock();
            }
            return true;
        } catch (Exception e) {
            log.error("企业微信发送异常: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean tryAcquire() {
        refreshWindow();
        RATE_LOCK.lock();
        try {
            if (dayCount >= maxPerDay) {
                log.warn("企业微信发送每日配额已达上限: {}", maxPerDay);
                return false;
            }
            if (minuteCount >= maxPerMinute) {
                log.warn("企业微信发送速率受限: 每分钟上限 {}", maxPerMinute);
                return false;
            }
            minuteCount++;
            dayCount++;
            return true;
        } finally {
            RATE_LOCK.unlock();
        }
    }

    /**
     * 发送Markdown消息（入队异步发送）
     */
    public boolean sendMarkdown(String title, String content) {
        startWorker();
        try {
            if (!content.contains(keyword)) {
                content = "**" + keyword + "**\n\n" + content;
            }
            QUEUE.put(new Msg(title, content));
            int minuteRemain = getMinuteRemaining();
            int dayRemain = getDayRemaining();
            if (dayRemain == 0) {
                log.warn("企业微信当日额度已满，消息已排队，今日剩余=0，队列长度={}", getQueueSize());
            } else if (minuteRemain == 0) {
                log.warn("企业微信当前分钟额度已满，消息已排队，本分钟剩余=0，队列长度={}", getQueueSize());
            }
            return true;
        } catch (Exception e) {
            log.error("入队企业微信消息异常：{}", e.getMessage(), e);
            return false;
        }
    }

    public int getMinuteRemaining() {
        RATE_LOCK.lock();
        try {
            return Math.max(0, maxPerMinute - minuteCount);
        } finally {
            RATE_LOCK.unlock();
        }
    }

    public int getDayRemaining() {
        RATE_LOCK.lock();
        try {
            return Math.max(0, maxPerDay - dayCount);
        } finally {
            RATE_LOCK.unlock();
        }
    }

    public int getQueueSize() {
        return QUEUE.size();
    }

    /**
     * 发送同步异常告警
     *
     * @param projectNum 项目编号
     * @param dataType   数据类型
     * @param errorMsg   错误信息
     */
    public void sendSyncAlarm(String projectNum, String dataType, String errorMsg) {
        if (!enabled) {
            log.warn("企业微信告警未启用");
            return;
        }

        String title = "数据同步异常告警";
        String content = String.format(
                "- **项目编号**：%s\n" +
                        "- **数据类型**：%s\n" +
                        "- **异常信息**：%s\n" +
                        "- **告警时间**：%s\n",
                projectNum, dataType, errorMsg, LocalDateTime.now()
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
                "- **项目编号**：%s\n" +
                        "- **数据类型**：%s\n" +
                        "- **第三方平台**：%d条\n" +
                        "- **本地数据库**：%d条\n" +
                        "- **差异**：%d条\n" +
                        "- **告警时间**：%s\n",
                projectNum, dataType, remoteCount, localCount,
                Math.abs(remoteCount - localCount), LocalDateTime.now()
        );

        sendMarkdown(title, content);
    }

    /**
     * 发送同步汇总报告
     */
    public void sendSyncReport(String dataType, String reportId, int totalProjects, int successProjects,
                                int totalCount, int successCount, int failCount) {
        if (!enabled) {
            return;
        }

        String title = "数据同步日报";
        StringBuilder content = new StringBuilder();
        content.append(String.format(
                "- **同步类型**：%s\n" +
                        "- **项目总数**：%d\n" +
                        "- **成功项目**：%d\n" +
                        "- **数据总量**：%d\n" +
                        "- **成功数**：%d\n" +
                        "- **失败数**：%d\n" +
                        "- **成功率**：%.2f%%\n" +
                        "- **报告时间**：%s\n",
                dataType, totalProjects, successProjects,
                totalCount, successCount, failCount,
                totalCount > 0 ? (successCount * 100.0 / totalCount) : 0,
                LocalDateTime.now()
        ));
        // 从配置文件读取外网可访问的真实地址，附带报告链接
        String reportUrl = reportBaseUrl + "/api/sync/tokenReport/" + reportId;
        content.append("\n[👉 点击查看详细报告明细](").append(reportUrl).append(")");

        sendMarkdown(title, content.toString());
    }
}
