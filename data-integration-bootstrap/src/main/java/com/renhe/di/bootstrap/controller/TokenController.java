package com.renhe.di.bootstrap.controller;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.api.TokenExpiredException;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.core.model.Result;
import com.renhe.di.dispatch.alarm.WechatAlarmService;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.ToolProject;
import com.renhe.di.store.service.DiProjectConfigService;
import com.renhe.di.store.service.DiProjectService;
import com.renhe.di.store.service.ToolProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Token管理Controller
 * 接收扫码结果并存储Token，自动完成项目入库
 */
@Slf4j
@RestController
@RequestMapping("sync")
public class TokenController {

    private static final String LOGIN_SID_KEY = "login:sid:";

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private DiProjectService diProjectService;

    @Autowired
    private ToolProjectService toolProjectService;

    @Autowired
    private ThirdPartyApiClient thirdPartyApiClient;

    @Autowired
    private WechatAlarmService alarmService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 接收扫码登录结果（批量）
     *
     * @param requestBodyList 扫码结果JSON列表
     * @return 处理结果
     */
    @PostMapping("sendTokenAndProjectNum")
    public Result<Void> receiveToken(@RequestBody List<String> requestBodyList) {
        if (requestBodyList == null || requestBodyList.isEmpty()) {
            return Result.fail(400, "接收到空的扫码数据");
        }

        StringBuilder errorMessages = new StringBuilder();
        for (String requestBody : requestBodyList) {
            try {
                processSingleToken(requestBody);
            } catch (Exception e) {
                log.error("处理单条扫码数据异常: {}", e.getMessage(), e);
                errorMessages.append(e.getMessage()).append("; ");
            }
        }

        if (!errorMessages.isEmpty()) {
            return Result.fail(500, "部分处理失败: " + errorMessages);
        }
        return Result.success();
    }

    /**
     * 处理单条扫码数据
     */
    private void processSingleToken(String requestBody) {
        JSONObject jsonObject = JSONUtil.parseObj(requestBody);

        // 判断是否是 getUserInfo 接口的响应
        String url = jsonObject.getStr("url", "");
        if (!url.contains("/getUserInfo")) {
            throw new IllegalArgumentException("非getUserInfo接口数据");
        }

        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null) {
            throw new IllegalArgumentException("缺少data字段");
        }

        String token = data.getStr("token");
        String username = data.getStr("username", "");
        String projectId = data.getStr("projectId", "");

        if (!StringUtils.hasLength(token)) {
            throw new IllegalArgumentException("Token为空");
        }

        log.info("接收到扫码结果：username={}, projectId={}", username, projectId);

        // 1. 存储Token到Redis（12小时有效期）
        tokenManager.storeToken(username, token);

        // 2. 查找或创建项目配置
        DiProjectConfig config = projectConfigService.getBySourceProjectNum(projectId);
        boolean isNewConfig = false;
        if (config == null) {
            config = new DiProjectConfig();
            config.setSourceProjectNum(projectId);
            config.setAccount(username);
            config.setPassword("----");
            config.setPlatNum(1);
            config.setStatus(1);
            isNewConfig = true;
        }
        config.setLastSyncTime(LocalDateTime.now());

        // 3. 调用第三方API验证项目信息
        JSONObject projectData = null;
        boolean tokenExpired = false;
        try {
            projectData = thirdPartyApiClient.getProjectData(projectId, username, config.getPassword());
        } catch (TokenExpiredException e) {
            // 401登录过期，需要移除Token
            tokenExpired = true;
            log.warn("项目【{}】Token已过期（401登录过期）: {}", projectId, e.getMessage());
        } catch (Exception e) {
            // 其他错误（风控/网络异常等），保留Token
            log.warn("获取项目详情异常（保留Token）: projectId={}, error={}", projectId, e.getMessage());
        }

        if (tokenExpired) {
            // 仅401登录过期才移除Token
            tokenManager.removeToken(username);
            log.warn("项目【{}】Token已过期，已移除Token", projectId);
            throw new RuntimeException("Token已过期（401登录过期），Token已移除，请重新扫码");
        }

        if (projectData == null) {
            // 临时错误，不移除Token
            log.warn("项目【{}】获取项目信息失败（临时错误，保留Token）", projectId);
            throw new RuntimeException("项目信息获取失败（临时错误），Token已保留，请稍后重试");
        }

        // 4. 更新/保存项目配置
        String projectName = projectData.getStr("fullName", "");
        config.setProjectName(projectName);
        if (isNewConfig) {
            projectConfigService.save(config);
            log.info("项目【{}】配置已自动创建", projectId);
        } else {
            projectConfigService.updateById(config);
        }

        // 5. 保存/更新 DiProject（项目数据）
        saveOrUpdateDiProject(projectId, projectData);

        // 6. 保存/更新 ToolProject
        saveOrUpdateToolProject(projectId, projectData);

        // 7. 存储扫码记录到Redis
        Date date = new Date();
        String key = DateUtil.format(date, DatePattern.PURE_DATE_PATTERN);
        String dateTimeText = DateUtil.format(date, DatePattern.CHINESE_DATE_TIME_PATTERN);
        stringRedisTemplate.opsForHash().put("token:project:" + key, projectId, dateTimeText);

        // 8. 存储登录信息到Redis
        stringRedisTemplate.opsForValue().set(
                "project:userinfo:" + projectId,
                requestBody
        );

        // 9. 首次登录判断：只在之前没有Token时才发通知
        String oldToken = stringRedisTemplate.opsForValue().get(LOGIN_SID_KEY + username);
        boolean isFirstLogin = !StringUtils.hasLength(oldToken);

        // 更新Redis中的Token（这一步在storeToken已经做了，但旧代码逻辑是先查再存）
        // 实际上 tokenManager.storeToken 已经设置了12小时有效期
        // 这里我们只需要判断是否是首次登录

        if (isFirstLogin) {
            // 发送扫码登录成功通知
            String notifyMsg = String.format("扫码查询项目成功:\n 项目名称: %s\n 项目编码: %s\n 查询账号: %s",
                    projectName, projectId, username);
            alarmService.sendSyncAlarm(projectId, "SCAN_LOGIN", notifyMsg);
            log.info("项目【{}】首次扫码登录成功，Token已存储", projectId);
        } else {
            log.info("项目【{}】扫码登录成功（Token续期），Token已存储", projectId);
        }
    }

    /**
     * 保存或更新 DiProject
     */
    private void saveOrUpdateDiProject(String sourceProjectNum, JSONObject projectData) {
        try {
            DiProject project = new DiProject();
            project.setId(projectData.getStr("id"));
            project.setSourceProjectNum(sourceProjectNum);
            project.setProjectNum(projectData.getStr("id"));
            project.setProjectName(projectData.getStr("fullName"));
            project.setRecordNumber(projectData.getStr("filingNo"));
            String diAreaCode = projectData.getStr("areaCode");
            if (StringUtils.hasLength(diAreaCode)) {
                if (diAreaCode.length() > 6) {
                    project.setAreaCode(diAreaCode.substring(0, 6));
                } else {
                    project.setAreaCode(String.format("%-6s", diAreaCode).replace(' ', '0'));
                }
            }
            project.setProjectStatus(projectData.getStr("projectState"));

            java.math.BigDecimal lon = projectData.getBigDecimal("longitude");
            java.math.BigDecimal lat = projectData.getBigDecimal("latitude");
            project.setLon(lon);
            project.setLat(lat);

            String actualBeginDate = projectData.getStr("actualBeginDate");
            if (actualBeginDate != null && !actualBeginDate.isEmpty()) {
                try {
                    project.setCommencementDate(java.time.LocalDate.parse(actualBeginDate.substring(0, 10)));
                } catch (Exception e) {
                    log.warn("开工日期解析失败: {}", actualBeginDate);
                }
            }

            // 检查是否已存在
            DiProject existing = diProjectService.getById(project.getId());
            if (existing != null) {
                diProjectService.updateById(project);
                log.debug("DiProject【{}】已更新", sourceProjectNum);
            } else {
                diProjectService.save(project);
                log.info("DiProject【{}】已创建", sourceProjectNum);
            }
        } catch (Exception e) {
            log.error("保存DiProject失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 保存或更新 ToolProject
     */
    private void saveOrUpdateToolProject(String sourceProjectNum, JSONObject projectData) {
        try {
            ToolProject toolProject = new ToolProject();
            toolProject.setProjectNum(sourceProjectNum);
            toolProject.setProjectName(projectData.getStr("fullName"));
            toolProject.setRecordNumber(projectData.getStr("filingNo"));
            String areaCode = projectData.getStr("areaCode");
            toolProject.setAreaCode(areaCode);
            toolProject.setProjectDetailedAddress(projectData.getStr("location"));
            toolProject.setLon(projectData.getStr("longitude", ""));
            toolProject.setLat(projectData.getStr("latitude", ""));
            toolProject.setContractNo(projectData.getStr("contractNo", ""));
            toolProject.setCommencementDate(projectData.getStr("beginDate", ""));
            toolProject.setXmcProjectNum(sourceProjectNum);
            toolProject.setUpdateTime(new Date());

            // 检查是否已存在
            boolean exists = toolProjectService.lambdaQuery()
                    .eq(ToolProject::getProjectNum, sourceProjectNum)
                    .exists();
            if (exists) {
                toolProjectService.updateById(toolProject);
                log.debug("ToolProject【{}】已更新", sourceProjectNum);
            } else {
                toolProject.setCreateTime(new Date());
                toolProjectService.save(toolProject);
                log.info("ToolProject【{}】已创建", sourceProjectNum);
            }
        } catch (Exception e) {
            log.error("保存ToolProject失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 查看网页版续期报告详情
     */
    @GetMapping(value = "tokenReport/{reportId}", produces = MediaType.TEXT_HTML_VALUE + ";charset=utf-8")
    @ResponseBody
    public String getTokenReportHtml(@PathVariable("reportId") String reportId) {
        String data = stringRedisTemplate.opsForValue().get("token:report:" + reportId);
        if (!StringUtils.hasLength(data)) {
            return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>报告已过期</title>"
                    + "<meta name='viewport' content='width=device-width, initial-scale=1.0'></head>"
                    + "<body style='background-color:#f7f8fa;'>"
                    + "<h2 style='text-align:center;margin-top:50px;color:#999;'>"
                    + "📭 报告不存在或已过期(有效期7天)</h2></body></html>";
        }

        JSONObject reportData = JSONUtil.parseObj(data);
        List<String> success = new ArrayList<>();
        if (reportData.containsKey("success")) {
            reportData.getJSONArray("success").forEach(item -> success.add(item.toString()));
        }
        List<String> expired = new ArrayList<>();
        if (reportData.containsKey("expired")) {
            reportData.getJSONArray("expired").forEach(item -> expired.add(item.toString()));
        }
        // 兼容旧格式：旧报告使用 failedInvalid 字段
        if (expired.isEmpty() && reportData.containsKey("failedInvalid")) {
            reportData.getJSONArray("failedInvalid").forEach(item -> expired.add(item.toString()));
        }
        List<String> tempError = new ArrayList<>();
        if (reportData.containsKey("tempError")) {
            reportData.getJSONArray("tempError").forEach(item -> tempError.add(item.toString()));
        }
        List<String> failedNoRecord = new ArrayList<>();
        if (reportData.containsKey("failedNoRecord")) {
            reportData.getJSONArray("failedNoRecord").forEach(item -> failedNoRecord.add(item.toString()));
        }
        String time = reportData.getStr("time");
        String reportDate = time != null && time.length() >= 10
                ? time.substring(0, 10) : DateUtil.today();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>")
                .append(reportDate).append(" 令牌续期报告</title>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<style>");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; padding: 15px; max-width: 800px; margin: 0 auto; background-color: #f7f8fa; color: #333; line-height: 1.5; }");
        html.append("h2 { text-align: center; color: #2c3e50; margin-bottom: 5px; font-size: 22px; }");
        html.append(".time { text-align: center; color: #7f8c8d; font-size: 13px; margin-bottom: 25px; }");
        html.append(".card { background: #fff; border-radius: 8px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }");
        html.append(".card h3 { margin-top: 0; padding-bottom: 12px; border-bottom: 1px solid #f0f0f0; font-size: 16px; }");
        html.append(".success h3 { color: #52c41a; border-bottom-color: #e8f5e9; }");
        html.append(".warning h3 { color: #faad14; border-bottom-color: #fff8e1; }");
        html.append(".danger h3 { color: #f5222d; border-bottom-color: #ffebee; }");
        html.append("ol { padding-left: 20px; margin-bottom: 0; font-size: 14px; }");
        html.append("li { padding: 8px 0; border-bottom: 1px dashed #f5f5f5; color: #555; }");
        html.append("li:last-child { border-bottom: none; }");
        html.append(".empty { color: #999; font-size: 14px; text-align: center; padding: 10px 0; }");
        html.append("</style></head><body>");

        html.append("<h2>").append(reportDate).append(" 数据采集令牌续期报告</h2>");
        html.append("<div class='time'>生成时间：").append(time).append("</div>");

        html.append("<div class='card danger'>");
        html.append("<h3>❌ Token已过期-已移除 (").append(expired.size()).append(" 个)</h3>");
        buildHtmlList(html, expired);
        html.append("</div>");

        html.append("<div class='card danger'>");
        html.append("<h3>❌ 扫码记录不存在 (").append(failedNoRecord.size()).append(" 个)</h3>");
        buildHtmlList(html, failedNoRecord);
        html.append("</div>");

        html.append("<div class='card warning'>");
        html.append("<h3>⚠️ 临时错误-保留Token等待恢复 (").append(tempError.size()).append(" 个)</h3>");
        buildHtmlList(html, tempError);
        html.append("</div>");

        html.append("<div class='card success'>");
        html.append("<h3>✅ 续期成功/无需扫码 (").append(success.size()).append(" 个)</h3>");
        buildHtmlList(html, success);
        html.append("</div>");

        html.append("</body></html>");

        return html.toString();
    }

    private void buildHtmlList(StringBuilder html, List<String> list) {
        if (list == null || list.isEmpty()) {
            html.append("<div class='empty'>暂无数据</div>");
            return;
        }
        html.append("<ol>");
        for (String item : list) {
            html.append("<li>").append(item).append("</li>");
        }
        html.append("</ol>");
    }
}
