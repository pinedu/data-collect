package com.renhe.di.ai.tool;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 远程 Agent 调用工具（model-gov 政务AI助手）
 * <p>
 * 将外部 AI Agent 包装为 Spring AI Tool，本地 LLM 通过 Function Calling 自动决定何时委托。
 * 调用前自动根据项目名称查询 tool_project 表获取 xmc_project_num（项目编号）。
 * <p>
 * 远程 Agent 协议：
 * - POST /chat/stream
 * - Accept: text/event-stream（SSE 流式返回）
 * - 请求头需携带 userid
 * - 请求体含 projectName / projectNum / message / session_id 等字段
 * <p>
 * SSE 流式响应完整收集后再返回给 LLM，LLM 整理后通过微信一次性回复用户。
 */
@Slf4j
@Component
public class RemoteAgentTool {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${ai.remote-agent.url:}")
    private String remoteAgentUrl;

    @Value("${ai.remote-agent.userid:}")
    private String userid;

    @Value("${ai.remote-agent.agent-id:default}")
    private String agentId;

    @Value("${ai.remote-agent.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${ai.remote-agent.default-project-name:}")
    private String defaultProjectName;

    /**
     * 调用远程 AI Agent 查询
     * <p>
     * 当本地工具无法满足用户需求时使用。
     * 完整收集远程 Agent 的 SSE 流式响应后返回，确保内容不丢失。
     *
     * @param projectName 项目名称，用于限定远程 Agent 的知识范围
     * @param question    用户的问题内容
     * @return 远程 Agent 的完整回复文本
     */
    @Tool(description = "调用远程专业AI助手查询。当本地工具（考勤/人员/项目/班组/SQL查询）无法满足用户需求时使用，"
            + "例如：政策法规咨询、合同条款分析、施工技术问题、行业知识、报告生成等超出本系统数据范围的问题。"
            + "必须提供项目名称，系统会自动查找对应项目编号。")
    public String askRemoteAgent(
            @ToolParam(description = "项目名称，用于限定远程Agent的知识范围，如'XX住宅楼项目'。用户未提及时使用默认值。") String projectName,
            @ToolParam(description = "用户的问题内容") String question) {

        if (remoteAgentUrl == null || remoteAgentUrl.isEmpty()) {
            log.warn("远程Agent未配置，跳过调用");
            return "远程AI助手未配置，无法回答该问题。";
        }

        if (question == null || question.trim().isEmpty()) {
            return "问题内容为空";
        }

        String effectiveProject = (projectName != null && !projectName.trim().isEmpty())
                ? projectName.trim()
                : defaultProjectName;

        log.info("调用远程Agent: projectName={}, question={}", effectiveProject,
                question.length() > 80 ? question.substring(0, 80) + "..." : question);

        try {
            // 根据项目名称动态查询 xmc_project_num
            String projectNum = lookupProjectNum(effectiveProject);
            if (projectNum == null || projectNum.isEmpty()) {
                log.warn("未找到项目编号, projectName={}", effectiveProject);
                return "未在系统中找到项目【" + effectiveProject + "】的编号，请确认项目名称是否正确。";
            }

            log.info("项目编号查询结果: projectName={}, projectNum={}", effectiveProject, projectNum);

            WebClient client = WebClient.builder()
                    .baseUrl(remoteAgentUrl)
                    .defaultHeader("userid", userid)
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();

            Map<String, Object> body = buildRequestBody(effectiveProject, projectNum, question);

            // 使用 ServerSentEvent 正确解析 SSE 流，完整收集所有事件
            List<ServerSentEvent<String>> events = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .doOnNext(event -> log.debug("SSE事件: id={}, event={}, data长度={}",
                            event.id(), event.event(),
                            event.data() != null ? event.data().length() : 0))
                    .onErrorResume(e -> {
                        log.warn("远程Agent SSE流异常: {}", e.getMessage());
                        // 返回一个包含错误信息的事件，不中断流
                        return reactor.core.publisher.Flux.just(
                                ServerSentEvent.<String>builder()
                                        .data("[SSE流异常: " + e.getMessage() + "]")
                                        .build());
                    })
                    .collectList()
                    .block(Duration.ofSeconds(timeoutSeconds + 30));

            if (events == null || events.isEmpty()) {
                log.warn("远程Agent返回空SSE流");
                return "远程AI助手未返回有效回复（空响应流）";
            }

            // 从所有 SSE 事件中提取并拼接完整文本
            String fullResponse = assembleEvents(events);

            if (fullResponse == null || fullResponse.trim().isEmpty()) {
                log.warn("远程Agent SSE事件中提取不到文本, 事件数={}", events.size());
                return "远程AI助手未返回有效回复（无法提取内容）";
            }

            log.info("远程Agent完整回复: 长度={}, preview={}",
                    fullResponse.length(),
                    fullResponse.length() > 120 ? fullResponse.substring(0, 120) + "..." : fullResponse);

            return fullResponse;

        } catch (Exception e) {
            log.error("调用远程Agent失败: {}", e.getMessage(), e);
            return "远程AI助手调用失败: " + e.getMessage();
        }
    }

    /**
     * 根据项目名称查询 tool_project 表获取 xmc_project_num
     */
    private String lookupProjectNum(String projectName) {
        try {
            List<String> exact = jdbcTemplate.queryForList(
                    "SELECT xmc_project_num FROM tool_project WHERE project_name = ? LIMIT 1",
                    String.class, projectName);
            if (!exact.isEmpty() && exact.get(0) != null) {
                return exact.get(0);
            }

            List<String> fuzzy = jdbcTemplate.queryForList(
                    "SELECT xmc_project_num FROM tool_project WHERE project_name LIKE ? LIMIT 1",
                    String.class, "%" + projectName + "%");
            if (!fuzzy.isEmpty() && fuzzy.get(0) != null) {
                return fuzzy.get(0);
            }

            return null;
        } catch (Exception e) {
            log.error("查询项目编号失败: projectName={}, error={}", projectName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建请求体，匹配 model-gov /chat/stream 实际协议
     */
    private Map<String, Object> buildRequestBody(String projectName, String projectNum, String question) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "send");
        body.put("agent_id", agentId);
        body.put("file_url", "");
        body.put("message", question);
        body.put("projectName", projectName != null ? projectName : "");
        body.put("projectNum", projectNum != null ? projectNum : "");
        body.put("session_id", UUID.randomUUID().toString());
        return body;
    }

    /**
     * 从 SSE 事件列表中提取完整文本内容
     * <p>
     * 处理流程：
     * 1. 遍历所有 ServerSentEvent，取 data() 字段
     * 2. 跳过 [DONE] 结束标记
     * 3. 如果 data 是 JSON，提取 content/text 字段
     * 4. 拼接所有片段为完整文本
     */
    private String assembleEvents(List<ServerSentEvent<String>> events) {
        StringBuilder sb = new StringBuilder();
        int dataCount = 0;

        for (ServerSentEvent<String> event : events) {
            String data = event.data();
            String eventName = event.event();

            if (data == null || data.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(data.trim())) {
                log.debug("收到SSE结束标记");
                continue;
            }

            dataCount++;
            String extracted = extractContentFromData(data, eventName);
            sb.append(extracted);
        }

        log.info("SSE事件处理: 总事件数={}, 有效data数={}, 拼接长度={}", events.size(), dataCount, sb.length());
        return sb.toString().trim();
    }

    /**
     * 从单条 SSE data 字段中提取文本
     * <p>
     * 兼容多种格式：
     * - 纯文本直接返回
     * - JSON {"content": "xxx"} 或 {"text": "xxx"}
     * - JSON 嵌套 {"data": {"content": "xxx"}}
     * - JSON 含 choices 数组（OpenAI 风格）
     * - tool_end 事件：JSON {"data": {"content": "xxx"}} 或 {"result": "xxx"}
     */
    private String extractContentFromData(String data, String eventName) {
        try {
            if (!data.startsWith("{")) {
                return data;
            }
            cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(data);

            // tool_end / tool_result 事件：优先取 result / output / data.content
            if ("tool_end".equals(eventName) || "tool_result".equals(eventName)) {
                String result = json.getStr("result");
                if (result != null) return result;
                String output = json.getStr("output");
                if (output != null) return output;
                cn.hutool.json.JSONObject nestedData = json.getJSONObject("data");
                if (nestedData != null) {
                    String nestedContent = nestedData.getStr("content");
                    if (nestedContent != null) return nestedContent;
                    String nestedText = nestedData.getStr("text");
                    if (nestedText != null) return nestedText;
                }
            }

            // 1. content 字段
            String content = json.getStr("content");
            if (content != null) {
                return content;
            }

            // 2. text 字段
            String text = json.getStr("text");
            if (text != null) {
                return text;
            }

            // 3. delta.content（OpenAI SSE 风格）
            cn.hutool.json.JSONObject delta = json.getJSONObject("delta");
            if (delta != null) {
                String deltaContent = delta.getStr("content");
                if (deltaContent != null) {
                    return deltaContent;
                }
            }

            // 4. choices[0].delta.content
            cn.hutool.json.JSONArray choices = json.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                cn.hutool.json.JSONObject choice = choices.getJSONObject(0);
                if (choice != null) {
                    cn.hutool.json.JSONObject choiceDelta = choice.getJSONObject("delta");
                    if (choiceDelta != null) {
                        String choiceContent = choiceDelta.getStr("content");
                        if (choiceContent != null) {
                            return choiceContent;
                        }
                    }
                    // choices[0].text
                    String choiceText = choice.getStr("text");
                    if (choiceText != null) {
                        return choiceText;
                    }
                }
            }

            // 5. data.content 嵌套
            cn.hutool.json.JSONObject nested = json.getJSONObject("data");
            if (nested != null) {
                String nestedContent = nested.getStr("content");
                if (nestedContent != null) {
                    return nestedContent;
                }
            }

            // 6. message 字段
            String message = json.getStr("message");
            if (message != null) {
                return message;
            }

            // 兜底：返回原始 JSON（方便排查未知格式）
            log.debug("未匹配已知JSON格式, data={}", data.length() > 200 ? data.substring(0, 200) : data);
            return data;
        } catch (Exception e) {
            return data;
        }
    }
}
