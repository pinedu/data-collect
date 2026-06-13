package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiProjectWarningIndicators;
import com.renhe.di.store.service.DiProjectWarningIndicatorsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 项目6个百分百预警指标同步任务
 * 每个项目一条记录，GET请求直接采集，无需分页/清洗管道
 */
@Slf4j
@Component
public class WarningIndicatorsSyncJob extends AbstractSyncJob {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private DiProjectWarningIndicatorsService warningIndicatorsService;

    @Override
    protected String getDataType() {
        return "WARNING_INDICATORS";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "项目预警指标同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        // 由 Pipeline 编排调用 syncSingleProject，此处仅做兜底
        return SyncResult.empty();
    }

    /**
     * 同步单个项目的预警指标数据（由 Pipeline 调用）
     */
    public SyncResult syncSingleProject(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String account = project.getAccount();
        String password = project.getPassword();

        try {
            JSONObject data = apiClient.getWarningIndicators(projectNum, account, password);
            if (data == null) {
                log.warn("项目【{}】预警指标返回空数据", projectNum);
                return SyncResult.of(1, 0, 1, 0);
            }

            DiProjectWarningIndicators entity = mapToEntity(projectNum, data);
            warningIndicatorsService.saveOrUpdate(entity);

            log.info("项目【{}】预警指标同步成功，月份={}", projectNum, entity.getDateMonth());
            return SyncResult.of(1, 1, 0, 0);
        } catch (Exception e) {
            log.error("项目【{}】预警指标同步失败: {}", projectNum, e.getMessage(), e);
            if (handleTokenExpired(e, account)) {
                return SyncResult.of(1, 0, 1, 0);
            }
            if (isAntiCrawlerMessage(e)) {
                return SyncResult.antiCrawler(1, 0, 1, 0);
            }
            return SyncResult.of(1, 0, 1, 0);
        }
    }

    /**
     * 将第三方API响应JSON映射为实体
     */
    private DiProjectWarningIndicators mapToEntity(String projectNum, JSONObject data) {
        DiProjectWarningIndicators e = new DiProjectWarningIndicators();
        e.setSourceProjectNum(projectNum);
        e.setProjectName(data.getStr("projectName"));
        e.setDateMonth(data.getStr("dateMonth"));

        // 工资保证金
        e.setIsStoreWageDeposit(data.getStr("isStoreWageDeposit"));
        e.setIsStoreWageDepositFailReason(data.getStr("isStoreWageDepositFailReason"));
        e.setDepositType(data.getStr("depositType"));
        e.setDepositAmount(parseBigDecimal(data.getStr("depositAmount")));
        e.setDepositValidity(data.getStr("depositValidity"));
        e.setDepositStatus(data.getStr("depositStatus"));
        e.setDepositFailReason(data.getStr("depositFailReason"));
        e.setDepositImgs(data.getStr("depositImgs"));

        // 劳动合同
        e.setIsSignContract(data.getStr("isSignContract"));
        e.setIsSignContractFailReason(data.getStr("isSignContractFailReason"));
        e.setSignContractNum(data.getInt("signContractNum"));
        e.setSignElectronicContractNum(data.getInt("signElectronicContractNum"));
        e.setOnJobNum(data.getInt("onJobNum"));
        e.setConstructionContractStatus(data.getStr("constructionContractStatus"));
        e.setConstructionContractUrl(data.getStr("constructionContractUrl"));

        // 实名制考勤
        e.setIsRealNameAttendance(data.getStr("isRealNameAttendance"));
        e.setIsRealNameAttendanceFailReason(data.getStr("isRealNameAttendanceFailReason"));
        e.setCurrentDayAttendPersonNum(data.getInt("currentDayAttendPersonNum"));
        e.setOnDutyPersonNum(data.getInt("onDutyPersonNum"));
        e.setPayAndAttendNum(data.getInt("payAndAttendNum"));
        e.setLastOfLastMonthAttendNum(data.getInt("lastOfLastMonthAttendNum"));
        e.setNoAttendNum(data.getInt("noAttendNum"));
        e.setNoContractAttendNum(data.getInt("noContractAttendNum"));

        // 分账拨付
        e.setIsSplitAppropriation(data.getStr("isSplitAppropriation"));
        e.setIsSplitAppropriationFailReason(data.getStr("isSplitAppropriationFailReason"));
        e.setAppropriationFailReason(data.getStr("appropriationFailReason"));

        // 银行代发
        e.setIsAgentPayment(data.getStr("isAgentPayment"));
        e.setIsAgentPaymentFailReason(data.getStr("isAgentPaymentFailReason"));
        e.setLastMonthPayNum(data.getInt("lastMonthPayNum"));
        e.setAvgSalaryAmount(parseBigDecimal(data.getStr("avgSalaryAmount")));
        e.setSalaryDay(data.getStr("salaryDay"));
        e.setOverOneHundredThousandNum(data.getInt("overOneHundredThousandNum"));
        e.setOverThreeHundredThousandNum(data.getInt("overThreeHundredThousandNum"));

        // 离场结算
        e.setIsExitSettlement(data.getStr("isExitSettlement"));
        e.setIsExitSettlementFailReason(data.getStr("isExitSettlementFailReason"));
        e.setExitWithSettlementNum(data.getInt("exitWithSettlementNum"));
        e.setExitWithNoSettlementNum(data.getInt("exitWithNoSettlementNum"));

        // 专户
        e.setIsOpenSpecialAccount(data.getStr("isOpenSpecialAccount"));
        e.setIsOpenSpecialAccountFailReason(data.getStr("isOpenSpecialAccountFailReason"));
        e.setAccountNum(data.getStr("accountNum"));
        e.setAccountBalance(parseBigDecimal(data.getStr("accountBalance")));
        e.setAccountReceiptsAmount(parseBigDecimal(data.getStr("accountReceiptsAmount")));
        e.setSettlementAmount(parseBigDecimal(data.getStr("settlementAmount")));
        e.setAccountEditTime(parseDateTime(data.getStr("accountEditTime")));
        e.setAccountVerifyStatus(data.getStr("accountVerifyStatus"));
        e.setAccountVerifyFailReason(data.getStr("accountVerifyFailReason"));
        e.setAccountTripartiteAgreement(data.getStr("accountTripartiteAgreement"));

        // 其他
        e.setIndicatorBoardUrl(data.getStr("indicatorBoardUrl"));
        e.setComplaintNum(data.getInt("complaintNum"));
        e.setObjectionNums(data.getInt("objectionNums"));
        e.setProjectCreateTime(parseDateTime(data.getStr("projectCreateTime")));

        return e;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDateTime.parse(value, DATETIME_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
