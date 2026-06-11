package com.renhe.di.ai.tool;

import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.entity.DiProjectWarningIndicators;
import com.renhe.di.store.mapper.DiProjectMapper;
import com.renhe.di.store.service.DiProjectWarningIndicatorsService;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 6个百分百预警指标工具
 * <p>
 * 查询项目合规预警状态：工资保证金、劳动合同、实名制考勤、
 * 分账拨付、银行代发、离场结算、专户开设等7大合规维度。
 */
@Component
public class WarningIndicatorsTool extends BaseDataTool {

    @Resource
    private ProjectNameResolver projectNameResolver;

    @Resource
    private DiProjectMapper projectMapper;

    @Resource
    private DiProjectWarningIndicatorsService warningService;

    // ========== 6+1 合规维度定义 ==========

    private record ComplianceItem(String name, String field, String failReasonField) {}

    private static final List<ComplianceItem> COMPLIANCE_ITEMS = List.of(
            new ComplianceItem("工资保证金", "isStoreWageDeposit", "isStoreWageDepositFailReason"),
            new ComplianceItem("劳动合同签订", "isSignContract", "isSignContractFailReason"),
            new ComplianceItem("实名制考勤", "isRealNameAttendance", "isRealNameAttendanceFailReason"),
            new ComplianceItem("分账拨付", "isSplitAppropriation", "isSplitAppropriationFailReason"),
            new ComplianceItem("银行代发", "isAgentPayment", "isAgentPaymentFailReason"),
            new ComplianceItem("离场结算", "isExitSettlement", "isExitSettlementFailReason"),
            new ComplianceItem("专户开设", "isOpenSpecialAccount", "isOpenSpecialAccountFailReason")
    );

    /**
     * 查询指定项目的所有预警指标状态
     */
    @Tool(description = "查询指定项目的6个百分百预警指标状态，返回各合规维度的通过/未通过状态及失败原因。项目名称必须精确")
    public String getProjectWarnings(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        DiProject project = findProjectByName(result.exactName());
        if (project == null) {
            return "未找到项目【" + result.exactName() + "】";
        }

        DiProjectWarningIndicators w = warningService.lambdaQuery()
                .eq(DiProjectWarningIndicators::getSourceProjectNum, project.getSourceProjectNum())
                .one();

        if (w == null) {
            return "项目【" + result.exactName() + "】暂无预警指标数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.exactName()).append(" 预警指标\n");
        sb.append("统计月份: ").append(w.getDateMonth() != null ? w.getDateMonth() : "未知").append("\n\n");

        int passCount = 0;
        int failCount = 0;

        for (ComplianceItem item : COMPLIANCE_ITEMS) {
            String status = getFieldValue(w, item.field());
            boolean pass = "是".equals(status);
            if (pass) passCount++;
            else failCount++;

            sb.append(pass ? "✅ " : "❌ ").append(item.name()).append(": ").append(status != null ? status : "未知").append("\n");
            if (!pass) {
                String reason = getFieldValue(w, item.failReasonField());
                if (reason != null && !reason.isBlank()) {
                    sb.append("   原因: ").append(reason).append("\n");
                }
            }
        }

        sb.append("\n合规率: ").append(passCount).append("/").append(passCount + failCount);

        // 附加关键数据指标
        sb.append("\n\n### 关键数据\n");
        appendIfNotNull(sb, "在职人数", w.getOnJobNum());
        appendIfNotNull(sb, "合同签订人数", w.getSignContractNum());
        appendIfNotNull(sb, "当日考勤人数", w.getCurrentDayAttendPersonNum());
        appendIfNotNull(sb, "在岗人数", w.getOnDutyPersonNum());
        appendIfNotNull(sb, "无考勤人数", w.getNoAttendNum());
        appendIfNotNull(sb, "无合同考勤人数", w.getNoContractAttendNum());
        appendIfNotNull(sb, "上月发放工资人数", w.getLastMonthPayNum());
        if (w.getAvgSalaryAmount() != null) {
            sb.append("  上月人均工资: ").append(w.getAvgSalaryAmount()).append("元\n");
        }
        appendIfNotNull(sb, "投诉数量", w.getComplaintNum());
        appendIfNotNull(sb, "异议数量", w.getObjectionNums());

        return buildReply(sb.toString());
    }

    /**
     * 全局合规巡检 — 列出所有存在红灯的项目
     */
    @Tool(description = "全局合规巡检，列出所有存在预警（未通过）的项目及其不合规项数量")
    public String getComplianceSummary() {
        List<DiProjectWarningIndicators> all = warningService.list();

        if (all.isEmpty()) {
            return "暂无预警指标数据";
        }

        List<String> alertProjects = new ArrayList<>();
        int totalPass = 0;
        int totalFail = 0;

        for (DiProjectWarningIndicators w : all) {
            int pass = 0, fail = 0;
            List<String> failItems = new ArrayList<>();

            for (ComplianceItem item : COMPLIANCE_ITEMS) {
                String status = getFieldValue(w, item.field());
                if ("是".equals(status)) {
                    pass++;
                } else {
                    fail++;
                    failItems.add(item.name());
                }
            }
            totalPass += pass;
            totalFail += fail;

            if (fail > 0) {
                alertProjects.add(w.getProjectName() + " (" + fail + "项未通过: " + String.join("、", failItems) + ")");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 全局合规巡检\n");
        sb.append("共 ").append(all.size()).append(" 个项目\n");
        sb.append("整体合规率: ").append(totalPass).append("/").append(totalPass + totalFail).append("\n\n");

        if (alertProjects.isEmpty()) {
            sb.append("🎉 所有项目全部合规！");
        } else {
            sb.append("⚠️ 存在预警的项目（").append(alertProjects.size()).append("个）:\n");
            for (String line : alertProjects) {
                sb.append("  ❌ ").append(line).append("\n");
            }
        }

        return buildReply(sb.toString());
    }

    /**
     * 查询项目工资保证金和专户状态
     */
    @Tool(description = "查询指定项目的工资保证金存储状态和专户开设情况，包括保证金金额、状态、开户账号余额等资金合规信息")
    public String getWageDepositStatus(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        DiProject project = findProjectByName(result.exactName());
        if (project == null) {
            return "未找到项目【" + result.exactName() + "】";
        }

        DiProjectWarningIndicators w = warningService.lambdaQuery()
                .eq(DiProjectWarningIndicators::getSourceProjectNum, project.getSourceProjectNum())
                .one();

        if (w == null) {
            return "项目【" + result.exactName() + "】暂无预警指标数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.exactName()).append(" 资金合规\n\n");

        sb.append("### 工资保证金\n");
        sb.append("  是否存储: ").append(nullSafe(w.getIsStoreWageDeposit())).append("\n");
        if (w.getDepositAmount() != null) {
            sb.append("  存储金额: ").append(w.getDepositAmount()).append("万元\n");
        }
        sb.append("  保证金类型: ").append(nullSafe(w.getDepositType())).append("\n");
        sb.append("  保证金状态: ").append(nullSafe(w.getDepositStatus())).append("\n");
        sb.append("  有效期: ").append(nullSafe(w.getDepositValidity())).append("\n");
        if (!"是".equals(w.getIsStoreWageDeposit())) {
            sb.append("  ⚠️ 失败原因: ").append(nullSafe(w.getIsStoreWageDepositFailReason())).append("\n");
        }

        sb.append("\n### 专户开设\n");
        sb.append("  是否开设: ").append(nullSafe(w.getIsOpenSpecialAccount())).append("\n");
        sb.append("  开户账号: ").append(nullSafe(w.getAccountNum())).append("\n");
        if (w.getAccountBalance() != null) {
            sb.append("  专户余额: ").append(w.getAccountBalance()).append("元\n");
        }
        if (w.getAccountReceiptsAmount() != null) {
            sb.append("  收款金额: ").append(w.getAccountReceiptsAmount()).append("元\n");
        }
        if (!"是".equals(w.getIsOpenSpecialAccount())) {
            sb.append("  ⚠️ 失败原因: ").append(nullSafe(w.getIsOpenSpecialAccountFailReason())).append("\n");
        }

        sb.append("\n### 分账拨付\n");
        sb.append("  是否拨付: ").append(nullSafe(w.getIsSplitAppropriation())).append("\n");
        if (!"是".equals(w.getIsSplitAppropriation())) {
            sb.append("  ⚠️ 失败原因: ").append(nullSafe(w.getIsSplitAppropriationFailReason())).append("\n");
        }
        if (w.getAppropriationFailReason() != null) {
            sb.append("  拨付失败原因: ").append(w.getAppropriationFailReason()).append("\n");
        }

        return buildReply(sb.toString());
    }

    /**
     * 查询项目劳动合同签订和银行代发状态
     */
    @Tool(description = "查询指定项目的劳动合同签订率和银行代发工资状态，包括签订人数、电子合同数、代发状态、发薪日期等")
    public String getContractSignStatus(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        DiProject project = findProjectByName(result.exactName());
        if (project == null) {
            return "未找到项目【" + result.exactName() + "】";
        }

        DiProjectWarningIndicators w = warningService.lambdaQuery()
                .eq(DiProjectWarningIndicators::getSourceProjectNum, project.getSourceProjectNum())
                .one();

        if (w == null) {
            return "项目【" + result.exactName() + "】暂无预警指标数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.exactName()).append(" 合同与代发\n\n");

        sb.append("### 劳动合同\n");
        sb.append("  是否签订: ").append(nullSafe(w.getIsSignContract())).append("\n");
        appendIfNotNull(sb, "  签订人数", w.getSignContractNum());
        appendIfNotNull(sb, "  电子合同数", w.getSignElectronicContractNum());
        appendIfNotNull(sb, "  在职人数", w.getOnJobNum());
        if (w.getSignContractNum() != null && w.getOnJobNum() != null && w.getOnJobNum() > 0) {
            int rate = (int) ((w.getSignContractNum() * 100.0) / w.getOnJobNum());
            sb.append("  签订率: ").append(rate).append("%\n");
        }
        if (!"是".equals(w.getIsSignContract())) {
            sb.append("  ⚠️ 未签原因: ").append(nullSafe(w.getIsSignContractFailReason())).append("\n");
        }

        sb.append("\n### 银行代发\n");
        sb.append("  代发状态: ").append(nullSafe(w.getIsAgentPayment())).append("\n");
        appendIfNotNull(sb, "  上月发放人数", w.getLastMonthPayNum());
        if (w.getAvgSalaryAmount() != null) {
            sb.append("  上月人均工资: ").append(w.getAvgSalaryAmount()).append("元\n");
        }
        sb.append("  发薪日期: ").append(nullSafe(w.getSalaryDay())).append("\n");
        appendIfNotNull(sb, "  超5万人数", w.getOverOneHundredThousandNum());
        appendIfNotNull(sb, "  超30万人数", w.getOverThreeHundredThousandNum());
        if ("否".equals(w.getIsAgentPayment()) || "预警".equals(w.getIsAgentPayment())) {
            sb.append("  ⚠️ 代发异常: ").append(nullSafe(w.getIsAgentPaymentFailReason())).append("\n");
        }

        sb.append("\n### 离场结算\n");
        sb.append("  是否结算: ").append(nullSafe(w.getIsExitSettlement())).append("\n");
        appendIfNotNull(sb, "  已结算人数", w.getExitWithSettlementNum());
        appendIfNotNull(sb, "  未结算人数", w.getExitWithNoSettlementNum());
        if (!"是".equals(w.getIsExitSettlement())) {
            sb.append("  ⚠️ 未结算原因: ").append(nullSafe(w.getIsExitSettlementFailReason())).append("\n");
        }

        return buildReply(sb.toString());
    }

    // ========== 工具方法 ==========

    private String getFieldValue(DiProjectWarningIndicators w, String fieldName) {
        return switch (fieldName) {
            case "isStoreWageDeposit" -> w.getIsStoreWageDeposit();
            case "isStoreWageDepositFailReason" -> w.getIsStoreWageDepositFailReason();
            case "isSignContract" -> w.getIsSignContract();
            case "isSignContractFailReason" -> w.getIsSignContractFailReason();
            case "isRealNameAttendance" -> w.getIsRealNameAttendance();
            case "isRealNameAttendanceFailReason" -> w.getIsRealNameAttendanceFailReason();
            case "isSplitAppropriation" -> w.getIsSplitAppropriation();
            case "isSplitAppropriationFailReason" -> w.getIsSplitAppropriationFailReason();
            case "isAgentPayment" -> w.getIsAgentPayment();
            case "isAgentPaymentFailReason" -> w.getIsAgentPaymentFailReason();
            case "isExitSettlement" -> w.getIsExitSettlement();
            case "isExitSettlementFailReason" -> w.getIsExitSettlementFailReason();
            case "isOpenSpecialAccount" -> w.getIsOpenSpecialAccount();
            case "isOpenSpecialAccountFailReason" -> w.getIsOpenSpecialAccountFailReason();
            default -> null;
        };
    }

    private void appendIfNotNull(StringBuilder sb, String label, Object value) {
        if (value != null) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "-";
    }

    private DiProject findProjectByName(String exactName) {
        return projectMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DiProject>()
                        .eq(DiProject::getProjectName, exactName)
                        .last("LIMIT 1"));
    }
}
