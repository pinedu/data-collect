package com.renhe.di.collect.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * 考勤Excel解析器
 * 解析第三方API返回的考勤列表Excel（xlsx），提取班组名、姓名、出勤天数
 */
@Slf4j
public class AttendanceExcelParser {

    /** 数据行起始索引（第4行，前3行为表头） */
    private static final int DATA_ROW_START = 3;

    /** 班组名称列索引 */
    private static final int COL_TEAM_NAME = 2;

    /** 姓名列索引 */
    private static final int COL_PERSON_NAME = 3;

    /** 出勤天数列索引 */
    private static final int COL_ATTEND_DAYS = 4;

    /**
     * 解析考勤Excel字节数据
     *
     * @param excelBytes  xlsx文件字节数组
     * @param projectNum  项目编号（用于结果填充）
     * @param dateMonth   日期月份（用于结果填充）
     * @return 解析结果列表，每条记录包含 teamName、personName、attDayNum、dateMonth、projectNum
     */
    public static List<Map<String, String>> parse(byte[] excelBytes, String projectNum, String dateMonth) {
        if (excelBytes == null || excelBytes.length == 0) {
            return Collections.emptyList();
        }

        List<Map<String, String>> resultList = new ArrayList<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            XSSFSheet sheet = workbook.getSheetAt(0);

            for (int i = DATA_ROW_START; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                Cell cellTeam = row.getCell(COL_TEAM_NAME);
                Cell cellName = row.getCell(COL_PERSON_NAME);
                Cell cellDays = row.getCell(COL_ATTEND_DAYS);

                // 姓名和出勤天数都为空则跳过
                if (cellName == null && cellDays == null) {
                    continue;
                }

                String teamName = getCellValueAsString(cellTeam);
                String personName = getCellValueAsString(cellName);
                String attDayNum = getCellValueAsString(cellDays);

                Map<String, String> dataMap = new HashMap<>(5);
                dataMap.put("teamName", teamName);
                dataMap.put("personName", personName);
                dataMap.put("attDayNum", attDayNum);
                dataMap.put("dateMonth", dateMonth);
                dataMap.put("projectNum", projectNum);
                resultList.add(dataMap);
            }
        } catch (IOException e) {
            log.error("项目【{}】月份【{}】解析Excel失败: {}", projectNum, dateMonth, e.getMessage(), e);
        }

        return resultList;
    }

    /**
     * 获取单元格的值并转换为字符串
     */
    private static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double numericValue = cell.getNumericCellValue();
                // 整数去掉小数部分
                yield numericValue == Math.floor(numericValue)
                        ? String.valueOf((long) numericValue)
                        : String.valueOf(numericValue);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        yield String.valueOf(cell.getNumericCellValue());
                    } catch (Exception ex) {
                        yield "";
                    }
                }
            }
            case BLANK -> "";
            default -> "";
        };
    }
}
