package com.renhe.di.collect.api;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpRequest;
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

/**
 * 第三方数据采集API客户端
 * 对接黔薪保平台接口
 */
@Slf4j
@Component
public class ThirdPartyApiClient {

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

    @PostConstruct
    public void init() {
        this.collectHost = apiHost + "/collect-api";
        this.projectUrl = collectHost + "/rrs/project/getById/";
        this.teamUrl = collectHost + "/rrs/team/list";
        this.personUrl = personHost + "/collect-api/rrs/person/list";
        this.attendanceUrl = collectHost + "/attend/list";
        this.payrollUrl = collectHost + "/rrs/salary/list";
        this.payrollDetailUrl = collectHost + "/rrs/salaryDetail/list";
        log.info("第三方API客户端初始化完成，host={}", collectHost);
    }

    /**
     * 获取项目详情
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

        if (!JSONUtil.isTypeJSON(body)) {
            log.warn("项目详情返回非JSON: {}", body);
            return null;
        }
        JSONObject result = JSONUtil.parseObj(body);
        Integer code = result.getInt("code");
        Integer success = result.getInt("success");
        if (success!=1) {
            String msg = result.getStr("message", result.getStr("msg", "未知错误"));
            log.warn("项目详情返回错误: code={}, msg={}", code, msg);
            return null;
        }
        return result.getJSONObject("data");
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

    // ==================== 私有方法 ====================

    /**
     * 解析第三方API响应，遇到错误时抛出异常（供 RateLimitStrategy 重试）
     *
     * @param body 响应体
     * @param desc 接口描述（用于日志）
     * @return data 节点
     * @throws RuntimeException 当code != 0 && code != 200时抛出
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
