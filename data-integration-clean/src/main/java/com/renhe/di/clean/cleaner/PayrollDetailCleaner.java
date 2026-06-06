package com.renhe.di.clean.cleaner;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.converter.ExtDataExtractor;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.DataCleaner;
import com.renhe.di.store.entity.DiPayrollDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 工资明细数据清洗器
 */
@Slf4j
@Component
public class PayrollDetailCleaner implements DataCleaner<JSONObject, DiPayrollDetail> {

    @Autowired
    private ExtDataExtractor extDataExtractor;

    @Override
    public DiPayrollDetail clean(JSONObject rawData, CleanContext ctx) {
        if (rawData == null) {
            return null;
        }

        DiPayrollDetail detail = new DiPayrollDetail();
        detail.setSourceProjectNum(ctx.getSourceProjectNum());
        detail.setSalaryId(rawData.getStr("salaryId"));
        detail.setDetailId(rawData.getStr("id"));
        detail.setPersonId(rawData.getStr("personId"));
        detail.setPersonName(rawData.getStr("name"));
        detail.setBankCardNo(rawData.getStr("cardNo"));
        detail.setPayableAmount(rawData.getBigDecimal("shouldPay", BigDecimal.ZERO));
        detail.setRealAmount(rawData.getBigDecimal("finalPay", BigDecimal.ZERO));
        detail.setPayMethod(rawData.getInt("payWay", 0) == 1 ? "线上" : "线下");

        String payDateStr = rawData.getStr("payDate");
        if (payDateStr != null && !payDateStr.isEmpty()) {
            try {
                detail.setPayDate(LocalDate.parse(payDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            } catch (Exception e) {
                log.warn("工资发放日期解析失败: {}", payDateStr);
            }
        }

        String payResult = rawData.getStr("payResult");
        String payStatus = "发送中";
        if (payResult != null && !payResult.isEmpty()) {
            JSONObject payResultObj = new JSONObject(payResult);
            Integer payRes = payResultObj.getInt("payRes");
            if (payRes != null) {
                if (payRes > 5) {
                    payStatus = "发送失败";
                } else if (payRes == 5) {
                    payStatus = "发送成功";
                }
            }
        }
        detail.setPayStatus(payStatus);
        detail.setExtData(extDataExtractor.extractExtData(rawData));
        detail.setDataVersion(1);
        detail.setSyncType(ctx.getDataType());
        detail.setSyncTime(LocalDateTime.now());

        return detail;
    }

    @Override
    public boolean supports(String dataType) {
        return "PAYROLL_DETAIL".equals(dataType);
    }
}
