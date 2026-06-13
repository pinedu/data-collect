package com.renhe.di.collect.api;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.renhe.di.collect.model.CollectContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 第三方数据采集API客户端
 * 对接黔薪保平台接口
 */
@Slf4j
@Component
public class ThirdPartyApiClient {

    /** 考勤接口按项目ID隔离的锁，同一项目同时只能有一个请求在执行 */
    private static final ConcurrentHashMap<String, ReentrantLock> ATTENDANCE_LOCKS = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定项目ID的考勤锁
     */
    private static ReentrantLock getAttendanceLock(String projectId) {
        return ATTENDANCE_LOCKS.computeIfAbsent(projectId, k -> new ReentrantLock());
    }

    @Resource
   private TokenManager tokenManager;

    @Autowired
    private BrowserFingerprint browserFingerprint;

    @Value("${collect.api.host:http://172.19.4.252:18001}")
    private String apiHost;

    @Value("${collect.api.person-host:http://172.19.4.252:18003}")
    private String personHost;

    @Value("${collect.api.app-key:368480924A6C78E2E8681551A7CF4C21}")
    private String appKey;

    @Value("${collect.api.sign-key:5EBE2294ECD0E0F08EAB7690D2A6EE69}")
    private String signKey;

    private String collectHost;
    private String projectUrl;
    private String teamUrl;
    private String personUrl;
    private String attendanceUrl;
    private String payrollUrl;
    private String payrollDetailUrl;
    private String warningIndicatorsUrl;
    private String salaryAndAttendUrl;
    private String downloadAttendanceListUrl;

    @PostConstruct
    public void init() {
        this.collectHost = apiHost + "/collect-api";
        this.projectUrl = collectHost + "/rrs/project/getById/";
        this.teamUrl = collectHost + "/rrs/team/list";
        this.personUrl = personHost + "/collect-api/rrs/person/list";
        this.attendanceUrl = collectHost + "/attend/list";
        this.payrollUrl = collectHost + "/rrs/salary/list";
        this.payrollDetailUrl = collectHost + "/rrs/salaryDetail/list";
        this.warningIndicatorsUrl = collectHost + "/sixIndex/newIndicators/getIndicator/";
        this.salaryAndAttendUrl = collectHost + "/sixIndex/warning/project/salaryAndAttend";
        this.downloadAttendanceListUrl = collectHost + "/sixIndex/project/downloadAttendanceList";
        log.info("第三方API客户端初始化完成，host={}", collectHost);
    }

    /**
     * 获取项目详情
     * 使用统一的 parseResponse 解析，确保 401登录过期等异常能正确抛出
     */
    public JSONObject getProjectData(String projectId, String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);

        String body = HttpRequest.get(projectUrl + projectId)
                .addHeaders(headMap)
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "项目详情");
    }

    /**
     * 获取班组列表（分页）
     */
    public JSONObject getTeamPage(String projectId, int pageNum, int pageSize, String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        bodyMap.put("pageNum", pageNum);
        bodyMap.put("pageSize", pageSize);
        bodyMap.put("projectId", projectId);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);

        String body = HttpRequest.post(teamUrl)
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap))
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "班组列表");
    }

    /**
     * 获取人员列表（分页+时间范围）
     */
    public JSONObject getPersonPage(String projectId, int pageNum, int pageSize,
                                     String beginDate, String endDate,
                                     String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(10);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        //{"status":"","isUpload":"","isTransient":"","pageNum":1,"pageSize":10,"state":1,"beginDate":"","endDate":""}
        bodyMap.put("status", "");
        bodyMap.put("isUpload", "");
        bodyMap.put("isTransient", "");
        bodyMap.put("pageNum", pageNum);
        bodyMap.put("pageSize", pageSize);
        bodyMap.put("beginDate", beginDate != null ? beginDate : "");
        bodyMap.put("endDate", endDate != null ? endDate : "");
        bodyMap.put("projectId", projectId);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);
        String body = HttpRequest.post(personUrl)
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap)).execute().body();
        return parseResponse(body, "人员列表");
    }




    /**
     * 获取考勤列表（分页+时间范围）
     */
    public JSONObject getAttendancePage(String projectId, int pageNum, int pageSize,
                                         String beginTime, String endTime,
                                         String account, String password) {
        ReentrantLock lock = getAttendanceLock(projectId);
        lock.lock();
        try {
            String timestamp = System.currentTimeMillis() + "";
            Map<String, Object> bodyMap = new HashMap<>(3);
            bodyMap.put("appkey", appKey);
            bodyMap.put("timestamp", timestamp);
            bodyMap.put("pageNum", pageNum);
            bodyMap.put("pageSize", pageSize);
            bodyMap.put("projectId", projectId);
            bodyMap.put("begin", beginTime);
            bodyMap.put("end", endTime);
            bodyMap.put("personName", "");
            bodyMap.put("invalid", 0);
            String signStr = getSignStr(bodyMap);
            String referer = "https://collect.gzldyg.com/attend/index";
            Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);
            headMap.put("Accept", "application/json, text/plain, */*");
            headMap.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headMap.put("Cache-Control", "no-cache");

            String body = HttpRequest.post(attendanceUrl)
                    .addHeaders(headMap)
                    .body(JSONUtil.toJsonStr(bodyMap))
                    .timeout(30000)
                    .execute().body();

            return parseResponse(body, "考勤列表");
        } catch (Exception e) {
            throw new RuntimeException("考勤接口请求异常", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取工资列表（分页+月份）
     */
    public JSONObject getPayrollPage(String projectId, int pageNum, int pageSize,
                                      String payMonth, String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        bodyMap.put("pageNum", pageNum);
        bodyMap.put("pageSize", pageSize);
        bodyMap.put("projectId", projectId);
        bodyMap.put("payMonth", payMonth);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);
        headMap.put("Accept", "application/json, text/plain, */*");
        headMap.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headMap.put("Cache-Control", "no-cache");

        String body = HttpRequest.post(payrollUrl)
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap))
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "工资列表");
    }

    /**
     * 获取工资明细列表（分页）
     */
    public JSONObject getPayrollDetailPage(String salaryId, int pageNum, int pageSize,
                                            String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        bodyMap.put("pageNum", pageNum);
        bodyMap.put("pageSize", pageSize);
        bodyMap.put("salaryId", salaryId);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);
        headMap.put("Accept", "application/json, text/plain, */*");
        headMap.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headMap.put("Cache-Control", "no-cache");

        String body = HttpRequest.post(payrollDetailUrl)
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap))
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "工资明细");
    }



    /**
     * 获取参建单位信息
     */
    public JSONObject getContractor(String projectId, String dwId, String personBelong,
                                     String position, String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        bodyMap.put("personBelong", personBelong);
        bodyMap.put("position", position);
        bodyMap.put("projectId", projectId);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);

        String body = HttpRequest.post(collectHost + "/project/specialPerson/list")
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap))
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "参建单位");
    }

    /**
     * 获取企业详情
     */
    public JSONObject getEnterpriseById(String dwId, String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);

        String body = HttpRequest.get(collectHost + "/rrs/ent/getById/" + dwId)
                .addHeaders(headMap)
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "企业详情");
    }

    /**
     * 获取项目6个百分百预警指标数据
     */
    public JSONObject getWarningIndicators(String projectNum, String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(3);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);

        String body = HttpRequest.get(warningIndicatorsUrl + projectNum)
                .addHeaders(headMap)
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "预警指标");
    }

    /**
     * 获取项目月份工资考勤统计信息（按年）
     *
     * @param projectNum 项目编号
     * @param whichYear  年份，如 "2025"
     * @return data节点，包含 list.total（年度汇总）和 list.list（各月明细）
     */
    public JSONObject getSalaryAndAttendStats(String projectNum, String whichYear,
                                               String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(5);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        bodyMap.put("projectNum", projectNum);
        bodyMap.put("whichYear", whichYear);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);

        String body = HttpRequest.post(salaryAndAttendUrl)
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap))
                .timeout(30000)
                .execute().body();

        return parseResponse(body, "工资考勤统计");
    }

    /**
     * 下载项目考勤列表（Excel文件）
     *
     * @param projectNum 项目编号
     * @param dateMonth  日期月份，如 "202501"
     * @return Excel文件字节数组（xlsx格式）
     * @throws RuntimeException 当返回非Excel内容或触发风控时抛出
     */
    public byte[] downloadAttendanceList(String projectNum, String dateMonth,
                                          String account, String password) {
        String timestamp = System.currentTimeMillis() + "";
        Map<String, Object> bodyMap = new HashMap<>(5);
        bodyMap.put("appkey", appKey);
        bodyMap.put("timestamp", timestamp);
        bodyMap.put("projectNum", projectNum);
        bodyMap.put("dateMonth", dateMonth);
        String signStr = getSignStr(bodyMap);
        String referer = "https://ldyg.guizhou.gov.cn/collect/";
        Map<String, String> headMap = getHeadMap(signStr, referer, timestamp, account, password);
        // Excel下载专用头
        headMap.put("Accept", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, */*");

        HttpResponse response = HttpRequest.post(downloadAttendanceListUrl)
                .addHeaders(headMap)
                .body(JSONUtil.toJsonStr(bodyMap))
                .timeout(60000)
                .execute();

        String contentType = response.header("Content-Type");
        byte[] bodyBytes = response.bodyBytes();

        // 如果返回JSON/HTML，说明请求失败或触发风控
        if (contentType != null && (contentType.contains("application/json") || contentType.contains("text/html"))) {
            String bodyStr = bodyBytes != null ? new String(bodyBytes) : "空响应";
            String errMsg = String.format("下载考勤列表返回非Excel内容: projectNum=%s, dateMonth=%s, body=%s",
                    projectNum, dateMonth, bodyStr.length() > 500 ? bodyStr.substring(0, 500) : bodyStr);
            log.error(errMsg);
            throw new RuntimeException(errMsg);
        }

        // 校验文件头：xlsx本质是ZIP，前两字节为 PK (0x50 0x4B)
        if (bodyBytes == null || bodyBytes.length < 4
                || bodyBytes[0] != 0x50 || bodyBytes[1] != 0x4B) {
            String bodyStr = bodyBytes != null ? new String(bodyBytes) : "空";
            String errMsg = String.format("下载考勤列表返回非Excel文件: projectNum=%s, dateMonth=%s, body=%s",
                    projectNum, dateMonth, bodyStr.length() > 500 ? bodyStr.substring(0, 500) : bodyStr);
            log.error(errMsg);
            throw new RuntimeException(errMsg);
        }

        log.info("项目【{}】月份【{}】考勤列表下载成功，大小={}bytes", projectNum, dateMonth, bodyBytes.length);
        return bodyBytes;
    }

    // ==================== 私有方法 ====================

    /**
     * 解析第三方API响应，遇到错误时抛出异常（供 RateLimitStrategy 重试）
     * 当 code=401 且 msg 含 "登录过期" 时抛出 TokenExpiredException，调用方可据此移除Token
     *
     * @param body 响应体
     * @param desc 接口描述（用于日志）
     * @return data 节点
     * @throws TokenExpiredException 当 code=401 且 msg 含 "登录过期" 时抛出
     * @throws RuntimeException 其他错误时抛出
     */
    private JSONObject parseResponse(String body, String desc) {
        if (!JSONUtil.isTypeJSON(body)) {
            String errMsg = String.format("%s返回非JSON: %s", desc, body);
            log.error(errMsg);
            throw new RuntimeException(errMsg);
        }
        JSONObject result = JSONUtil.parseObj(body);
        Integer code = result.getInt("code");
        Integer success = result.getInt("success");
        if (success!=1) {
            String msg = result.getStr("message", result.getStr("msg", "未知错误"));
            // 构建完整错误信息，确保 "系统异常" 等关键词可以被 RateLimitStrategy 识别
            String errMsg = String.format("%s返回错误: code=%d, msg=%s", desc, code, msg);

            // code=401 + msg含"登录过期" → Token已失效，需移除
            if (code != null && code == 401 && msg != null && msg.contains("登录过期")) {
                log.warn("Token已过期（401登录过期），需移除: {}", errMsg);
                throw new TokenExpiredException(errMsg);
            }

            log.warn(errMsg);
            throw new RuntimeException(errMsg);
        }
        return result.getJSONObject("data");
    }

    public String getSignStr(Map<String, Object> bodyMap) {
        String sortParam = createLinkStringByGet(bodyMap);
        sortParam = signKey + sortParam + signKey;
        String signStr = sortParam.toUpperCase(Locale.ROOT);
        signStr = DigestUtil.md5Hex(signStr);
        return signStr;
    }
    /**
     * 把数组所有元素排序，并按照“参数=参数值”的模式用“&”字符拼接成字符串
     *
     * @param params 需要排序并参与字符拼接的参数组
     * @return 拼接后字符串
     */
    public static String createLinkStringByGet(Map<String, Object> params) {
        StringBuilder preStr = new StringBuilder();
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.get(key).toString();
            if (i == keys.size() - 1) {
                // 拼接时，不包括最后一个&字符
                preStr.append(key).append(value);
            } else {
                preStr.append(key).append(value);
            }
        }
        return preStr.toString();
    }



    public Map<String, String> getHeadMap(String signStr, String referer, String timestamp,
                                           String account, String password) {
        Map<String, String> headMap = new HashMap<>();
        // 业务认证头（不可变）
        String token = tokenManager.getToken(account, password);
        if (StringUtils.hasLength(token)) {
            headMap.put("Logininfo", token);
        }
        headMap.put("Appkey", appKey);
        headMap.put("Signcode", signStr);
        headMap.put("Timestamp", timestamp);
        headMap.put("Referer", referer);
        headMap.put("Origin", "https://ldyg.guizhou.gov.cn");
        headMap.put("Content-Type", "application/json");
        headMap.put("Accept", "application/json, text/plain, */*");
        headMap.put("Cache-Control", "no-cache");
        // 浏览器指纹头（随机化，按账号缓存，包含 User-Agent / Sec-Ch-* / Sec-Fetch-* 等）
        headMap.putAll(browserFingerprint.getHeaders(account));
        return headMap;
    }

    /**
     * 获取考勤数据总量（用于一致性校验）
     */
    public int getAttendanceTotalCount(CollectContext ctx) {
        try {
            JSONObject result = getAttendancePage(
                    ctx.getSourceProjectNum(), 1, 1,
                    ctx.getBeginTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    ctx.getEndTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    ctx.getAccount(), ctx.getPassword());
            return result.getInt("total", 0);
        } catch (Exception e) {
            log.error("获取考勤总量失败", e);
            return -1;
        }
    }

    /**
     * 获取工资数据总量（用于一致性校验）
     */
    public int getPayrollTotalCount(CollectContext ctx) {
        try {
            JSONObject result = getPayrollPage(
                    ctx.getSourceProjectNum(), 1, 1,
                    "", ctx.getAccount(), ctx.getPassword());
            return result.getInt("total", 0);
        } catch (Exception e) {
            log.error("获取工资总量失败", e);
            return -1;
        }
    }
}
