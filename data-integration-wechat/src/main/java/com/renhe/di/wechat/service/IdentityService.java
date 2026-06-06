package com.renhe.di.wechat.service;

import com.renhe.di.store.entity.WechatUser;
import com.renhe.di.store.mapper.WechatUserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 身份识别服务
 * <p>
 * 处理新用户首次对话时的昵称采集流程：
 * 1. 检测 wechat_user.user_name 是否为空
 * 2. 引导用户输入昵称
 * 3. 校验、保存昵称，计算用户序号
 * 4. 发送欢迎语
 */
@Slf4j
@Service
public class IdentityService {

    @Resource
    private WechatUserMapper wechatUserMapper;

    /** 昵称最大长度 */
    private static final int NICKNAME_MAX_LEN = 20;

    /**
     * 检查用户是否已有昵称（user_name 不为空）
     *
     * @param userId wechat_user 表主键
     * @return true=已注册（有昵称），false=未注册（需采集昵称）
     */
    public boolean hasUserName(Long userId) {
        WechatUser user = wechatUserMapper.selectById(userId);
        if (user == null) {
            log.warn("[身份识别] 用户不存在, userId={}, 视为已注册跳过采集", userId);
            return true;
        }
        String userName = user.getUserName();
        boolean hasName = userName != null && !userName.trim().isEmpty();
        log.info("[身份识别] userId={}, userName={}, hasName={}",
                userId, hasName ? userName : "(空)", hasName);
        return hasName;
    }

    /**
     * 保存昵称并返回用户注册序号 + 欢迎语
     * <p>
     * 事务保证：先更新 user_name，再查询 created_at 计算序号。
     * 使用 COUNT(*) WHERE created_at <= 当前用户 created_at，保证并发安全。
     *
     * @param userId   wechat_user 表主键
     * @param userName 用户输入的昵称
     * @return 格式化后的欢迎语，null 表示保存失败
     */
    @Transactional(rollbackFor = Exception.class)
    public String saveAndBuildWelcome(Long userId, String userName) {
        // 1. 写入 user_name
        int updated = wechatUserMapper.updateUserName(userId, userName);
        if (updated <= 0) {
            log.error("更新 user_name 失败, userId={}", userId);
            return null;
        }

        // 2. 重新查询，获取 createdAt
        WechatUser user = wechatUserMapper.selectById(userId);
        if (user == null || user.getCreatedAt() == null) {
            log.error("查询用户失败或缺少 createdAt, userId={}", userId);
            return null;
        }

        // 3. 统计用户序号（包含自身，Count 从 1 开始）
        int rank = wechatUserMapper.countRegisteredBefore(user.getCreatedAt());

        log.info("昵称保存成功, userId={}, userName={}, rank={}", userId, userName, rank);
        return buildWelcomeMessage(userName, rank);
    }

    /**
     * 校验昵称有效性
     *
     * @param input 用户原始输入
     * @return 校验通过返回 trimmed 昵称，失败返回 null
     */
    public String validateNickname(String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.length() > NICKNAME_MAX_LEN) {
            return null;
        }

        // 过滤纯符号、纯数字等无意义输入
        if (isInvalidNickname(trimmed)) {
            return null;
        }

        return trimmed;
    }

    /**
     * 判断昵称是否无效（纯符号、纯数字、含敏感词）
     */
    private boolean isInvalidNickname(String nickname) {
        int letterOrCjk = 0;
        for (int i = 0; i < nickname.length(); i++) {
            char c = nickname.charAt(i);
            if (Character.isLetter(c) || Character.isIdeographic(c)) {
                letterOrCjk++;
            }
        }
        // 必须包含至少 1 个字母或汉字
        return letterOrCjk == 0;
    }

    /**
     * 构建欢迎语
     *
     * @param userName 用户昵称
     * @param rank     用户注册序号（从 1 开始）
     */
    public String buildWelcomeMessage(String userName, int rank) {
        return "## \uD83C\uDF89 欢迎加入筑采助手\n\n"
                + "> \uD83D\uDC4B 嗨！**" + userName + "**，终于等到你啦\n"
                + "> \uD83C\uDF1F 你可是我们的第 **" + rank + "** 位小伙伴哦\n\n"
                + "---\n\n"
                + "### \uD83E\uDD14 我能帮你做些什么？\n\n"
                + "| \uD83D\uDEE0\uFE0F 功能 | \uD83D\uDCA1 你可以这样问 |\n"
                + "|---|---|\n"
                + "| \uD83C\uDFD7\uFE0F **查项目** | \"查一下最近的项目进度\" |\n"
                + "| \uD83D\uDCC5 **看考勤** | \"最近一周谁没打卡\" |\n"
                + "| \uD83D\uDC65 **管班组** | \"列出所有在册班组\" |\n"
                + "| \uD83D\uDCB0 **算工资** | \"本月各班组工资汇总\" |\n"
                + "| \uD83D\uDEE1\uFE0F **问安全** | \"高处作业有哪些要求\" |\n\n"
                + "---\n\n"
                + "> \uD83C\uDF3F 安全规范、施工技术、劳务管理、材料把控……\n"
                + "> \uD83D\uDE0A 我都会陪着你，一起搞定每一天的工作\n"
                + "> \uD83D\uDE80 现在就试试吧，想问什么尽管来~\n";
    }

    /**
     * 昵称采集引导语（首次询问）
     */
    public String getNicknamePrompt() {
        return "## \uD83D\uDC4B 您好！我是筑采助手\n\n"
                + "> 专注为建筑工地领域提供专业解答 \uD83C\uDFD7\uFE0F\n\n"
                + "在开始之前，请问我该怎么称呼您呢？（回复您的昵称或姓名）";
    }

    /**
     * 昵称校验失败提示
     */
    public String getInvalidNicknamePrompt() {
        return "\uD83D\uDE05 这个称呼格式不太对，请重新输入一个方便称呼您的名字吧（1~20个字符，请包含中文或字母）。";
    }

    /**
     * AI 未能从用户消息中提取到昵称的追问话术
     */
    public String getExtractionFailedPrompt() {
        return "\uD83E\uDD14 不好意思，我没能识别出您的名字。可以再说一次您希望我怎么称呼您吗？（比如\"叫我张三\"、\"我叫李四\"）";
    }

    /**
     * 连续失败 3 次后跳过采集的提示
     */
    public String getSkipNicknamePrompt() {
        return "\uD83D\uDC4C 好的，您可以先开始提问。之后随时可以告诉我您的称呼。";
    }
}
