package com.renhe.di.bootstrap.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.core.model.Result;
import com.renhe.di.dispatch.alarm.DingTalkAlarmService;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Token管理Controller
 * 接收扫码结果并存储Token
 */
@Slf4j
@RestController
@RequestMapping("/token")
public class TokenController {

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private DingTalkAlarmService alarmService;

    /**
     * 接收扫码登录结果
     *
     * @param requestBody 扫码结果JSON
     * @return 处理结果
     */
    @PostMapping("/receive")
    public Result<Void> receiveToken(@RequestBody String requestBody) {
        try {
            JSONObject jsonObject = JSONUtil.parseObj(requestBody);

            // 判断是否是 getUserInfo 接口的响应
            String url = jsonObject.getStr("url", "");
            if (!url.contains("/getUserInfo")) {
                return Result.fail(400, "非getUserInfo接口数据");
            }

            JSONObject data = jsonObject.getJSONObject("data");
            if (data == null) {
                return Result.fail(400, "缺少data字段");
            }

            String token = data.getStr("token");
            String username = data.getStr("username", "");
            String projectId = data.getStr("projectId", "");

            if (!StringUtils.hasLength(token)) {
                return Result.fail(400, "Token为空");
            }

            log.info("接收到扫码结果：username={}, projectId={}", username, projectId);

            // 存储Token到Redis
            tokenManager.storeToken(username, token);

            // 查找项目配置
            DiProjectConfig config = projectConfigService.getBySourceProjectNum(projectId);
            if (config != null) {
                // 更新最后同步时间
                config.setLastSyncTime(LocalDateTime.now());
                projectConfigService.updateById(config);

                // 发送钉钉通知
                String notifyMsg = String.format("扫码查询项目成功:\n 项目名称: %s\n 项目编码: %s\n 查询账号: %s",
                        config.getProjectName(), projectId, username);
                alarmService.sendSyncAlarm(projectId, "SCAN_LOGIN", notifyMsg);

                log.info("项目【{}】扫码登录成功，Token已存储", projectId);
            } else {
                log.warn("项目【{}】未在配置表中找到", projectId);
                return Result.fail(404, "项目配置不存在");
            }

            return Result.success();
        } catch (Exception e) {
            log.error("处理扫码数据异常", e);
            return Result.fail("处理异常：" + e.getMessage());
        }
    }

    /**
     * 手动刷新指定账号的Token
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refreshToken(@RequestParam String account,
                                                     @RequestParam(required = false) String password) {
        String newToken = tokenManager.refreshToken(account, password);
        if (newToken != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("token", newToken);
            return Result.success(data);
        } else {
            return Result.fail("Token刷新失败，请尝试重新扫码");
        }
    }

    /**
     * 查询Token状态
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getTokenStatus(@RequestParam String account) {
        boolean hasToken = tokenManager.hasToken(account);
        long ttl = tokenManager.getTokenTtl(account);

        Map<String, Object> data = new HashMap<>();
        data.put("account", account);
        data.put("hasToken", hasToken);
        data.put("ttlHours", ttl);

        return Result.success(data);
    }
}
