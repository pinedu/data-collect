package com.renhe.di.clean.cleaner;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.converter.ExtDataExtractor;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.DataCleaner;
import com.renhe.di.store.entity.DiPayroll;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工资主表数据清洗器
 */
@Slf4j
@Component
public class PayrollCleaner implements DataCleaner<JSONObject, DiPayroll> {

    @Autowired
    private ExtDataExtractor extDataExtractor;

    @Override
    public DiPayroll clean(JSONObject rawData, CleanContext ctx) {
        if (rawData == null) {
            return null;
        }

        DiPayroll payroll = new DiPayroll();
        payroll.setSourceProjectNum(ctx.getSourceProjectNum());
        payroll.setSalaryId(rawData.getStr("id"));
        payroll.setSalaryMonth(rawData.getStr("payMonth"));
        payroll.setTotalAmount(rawData.getBigDecimal("totalAmount", BigDecimal.ZERO));
        payroll.setPersonCount(rawData.getInt("personCount", 0));
        payroll.setPayStatus(rawData.getStr("payStatus"));
        payroll.setSubmitNo(rawData.getStr("submitNo"));
        payroll.setExtData(extDataExtractor.extractExtData(rawData));
        payroll.setDataVersion(1);
        payroll.setSyncType(ctx.getDataType());
        payroll.setSyncTime(LocalDateTime.now());

        return payroll;
    }

    @Override
    public boolean supports(String dataType) {
        return "PAYROLL".equals(dataType);
    }
}
