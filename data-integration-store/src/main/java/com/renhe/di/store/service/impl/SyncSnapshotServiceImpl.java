package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.mapper.DiSyncSnapshotMapper;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步快照服务实现
 */
@Slf4j
@Service
public class SyncSnapshotServiceImpl extends ServiceImpl<DiSyncSnapshotMapper, DiSyncSnapshot> implements SyncSnapshotService {

    @Override
    public void saveSnapshot(String sourceProjectNum, String dataType, int thirdPartyTotal, int localTotal) {
        // 查询今天是否已有同一项目+数据类型的全局快照记录（monthId IS NULL），有则更新，无则新增
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        DiSyncSnapshot exist = lambdaQuery()
                .eq(DiSyncSnapshot::getSourceProjectNum, sourceProjectNum)
                .eq(DiSyncSnapshot::getDataType, dataType)
                .isNull(DiSyncSnapshot::getMonthId)
                .ge(DiSyncSnapshot::getSyncTime, todayStart)
                .lt(DiSyncSnapshot::getSyncTime, todayEnd)
                .last("LIMIT 1")
                .one();

        if (exist != null) {
            exist.setThirdPartyTotal(thirdPartyTotal);
            exist.setLocalTotal(localTotal);
            exist.setSyncTime(LocalDateTime.now());
            updateById(exist);
            log.debug("更新同步快照: 项目={}, 类型={}, 第三方={}, 本地={}",
                    sourceProjectNum, dataType, thirdPartyTotal, localTotal);
        } else {
            DiSyncSnapshot snapshot = new DiSyncSnapshot();
            snapshot.setSourceProjectNum(sourceProjectNum);
            snapshot.setDataType(dataType);
            snapshot.setThirdPartyTotal(thirdPartyTotal);
            snapshot.setLocalTotal(localTotal);
            snapshot.setSyncTime(LocalDateTime.now());
            try {
                save(snapshot);
                log.info("创建同步快照: 项目={}, 类型={}, 第三方={}, 本地={}",
                        sourceProjectNum, dataType, thirdPartyTotal, localTotal);
            } catch (DataIntegrityViolationException e) {
                // 极低概率的并发竞态：同项目同类型全局快照在同一秒内由并发流水线插入
                // 此时唯一键已含 month_id，月份快照与全局快照不再碰撞
                log.warn("全局快照并发插入冲突，回退更新: 项目={}, 类型={}",
                        sourceProjectNum, dataType);
                DiSyncSnapshot retryExist = lambdaQuery()
                        .eq(DiSyncSnapshot::getSourceProjectNum, sourceProjectNum)
                        .eq(DiSyncSnapshot::getDataType, dataType)
                        .isNull(DiSyncSnapshot::getMonthId)
                        .ge(DiSyncSnapshot::getSyncTime, todayStart)
                        .lt(DiSyncSnapshot::getSyncTime, todayEnd)
                        .last("LIMIT 1")
                        .one();
                if (retryExist != null) {
                    retryExist.setThirdPartyTotal(thirdPartyTotal);
                    retryExist.setLocalTotal(localTotal);
                    retryExist.setSyncTime(LocalDateTime.now());
                    updateById(retryExist);
                    log.info("全局快照并发冲突后更新成功: 项目={}, 类型={}, 第三方={}, 本地={}",
                            sourceProjectNum, dataType, thirdPartyTotal, localTotal);
                }
            }
        }
    }

    @Override
    public List<DiSyncSnapshot> getLatestByProject(String sourceProjectNum) {
        return baseMapper.selectLatestByProject(sourceProjectNum);
    }

    @Override
    public DiSyncSnapshot getMonthSnapshot(String sourceProjectNum, String dataType, String monthId) {
        return lambdaQuery()
                .eq(DiSyncSnapshot::getSourceProjectNum, sourceProjectNum)
                .eq(DiSyncSnapshot::getDataType, dataType)
                .eq(DiSyncSnapshot::getMonthId, monthId)
                .orderByDesc(DiSyncSnapshot::getSyncTime)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public void saveMonthSnapshot(String sourceProjectNum, String dataType, String monthId,
                                  LocalDateTime monthSyncDate, int monthThirdPartyTotal) {
        // 防御性检查：第三方总量为0时绝不保存，否则会导致下次 0>=0 跳过逻辑死锁
        if (monthThirdPartyTotal <= 0) {
            log.warn("拒绝保存月份快照（thirdTotal={}<=0）: 项目={}, 类型={}, 月份={}",
                    monthThirdPartyTotal, sourceProjectNum, dataType, monthId);
            return;
        }
        DiSyncSnapshot exist = getMonthSnapshot(sourceProjectNum, dataType, monthId);
        if (exist != null) {
            exist.setMonthSyncDate(monthSyncDate);
            exist.setMonthThirdPartyTotal(monthThirdPartyTotal);
            exist.setSyncTime(LocalDateTime.now());
            updateById(exist);
            log.debug("更新月份快照: 项目={}, 类型={}, 月份={}, syncDate={}, thirdTotal={}",
                    sourceProjectNum, dataType, monthId, monthSyncDate, monthThirdPartyTotal);
        } else {
            DiSyncSnapshot snapshot = new DiSyncSnapshot();
            snapshot.setSourceProjectNum(sourceProjectNum);
            snapshot.setDataType(dataType);
            snapshot.setMonthId(monthId);
            snapshot.setMonthSyncDate(monthSyncDate);
            snapshot.setMonthThirdPartyTotal(monthThirdPartyTotal);
            snapshot.setSyncTime(LocalDateTime.now());
            try {
                save(snapshot);
                log.info("创建月份快照: 项目={}, 类型={}, 月份={}, syncDate={}, thirdTotal={}",
                        sourceProjectNum, dataType, monthId, monthSyncDate, monthThirdPartyTotal);
            } catch (DataIntegrityViolationException e) {
                // 极低概率的并发竞态：同项目同数据类型同月份的月份快照由并发流水线在同一秒内插入
                // 唯一键 uk_project_type_month_date 已含 month_id，仅同 month_id 才会碰撞
                log.warn("月份快照插入唯一键冲突，回退更新: 项目={}, 类型={}, 月份={}",
                        sourceProjectNum, dataType, monthId);
                DiSyncSnapshot retryExist = getMonthSnapshot(sourceProjectNum, dataType, monthId);
                if (retryExist != null) {
                    retryExist.setMonthSyncDate(monthSyncDate);
                    retryExist.setMonthThirdPartyTotal(monthThirdPartyTotal);
                    retryExist.setSyncTime(LocalDateTime.now());
                    updateById(retryExist);
                    log.info("月份快照冲突后更新成功: 项目={}, 类型={}, 月份={}, syncDate={}, thirdTotal={}",
                            sourceProjectNum, dataType, monthId, monthSyncDate, monthThirdPartyTotal);
                } else {
                    log.error("月份快照冲突后查询仍为空，放弃保存: 项目={}, 类型={}, 月份={}",
                            sourceProjectNum, dataType, monthId);
                }
            }
        }
    }

    @Override
    public void savePageCheckpoint(String sourceProjectNum, String dataType, String monthId,
                                   int lastCollectedPage, int monthTotalPages, int monthThirdPartyTotal) {
        if (monthThirdPartyTotal <= 0) {
            return;
        }
        DiSyncSnapshot exist = getMonthSnapshot(sourceProjectNum, dataType, monthId);
        if (exist != null) {
            exist.setLastCollectedPage(lastCollectedPage);
            exist.setMonthTotalPages(monthTotalPages);
            exist.setMonthThirdPartyTotal(monthThirdPartyTotal);
            exist.setSyncTime(LocalDateTime.now());
            updateById(exist);
        } else {
            DiSyncSnapshot snapshot = new DiSyncSnapshot();
            snapshot.setSourceProjectNum(sourceProjectNum);
            snapshot.setDataType(dataType);
            snapshot.setMonthId(monthId);
            snapshot.setLastCollectedPage(lastCollectedPage);
            snapshot.setMonthTotalPages(monthTotalPages);
            snapshot.setMonthThirdPartyTotal(monthThirdPartyTotal);
            snapshot.setSyncTime(LocalDateTime.now());
            try {
                save(snapshot);
            } catch (DataIntegrityViolationException e) {
                log.warn("月份检查点插入冲突，回退更新: 项目={}, 类型={}, 月份={}", sourceProjectNum, dataType, monthId);
                DiSyncSnapshot retryExist = getMonthSnapshot(sourceProjectNum, dataType, monthId);
                if (retryExist != null) {
                    retryExist.setLastCollectedPage(lastCollectedPage);
                    retryExist.setMonthTotalPages(monthTotalPages);
                    retryExist.setMonthThirdPartyTotal(monthThirdPartyTotal);
                    retryExist.setSyncTime(LocalDateTime.now());
                    updateById(retryExist);
                }
            }
        }
    }

    @Override
    public void markMonthComplete(String sourceProjectNum, String dataType, String monthId,
                                   int monthThirdPartyTotal) {
        // lastCollectedPage = -1 表示该月份已全部采集完成
        savePageCheckpoint(sourceProjectNum, dataType, monthId, -1, 0, monthThirdPartyTotal);
        log.info("月份【{}】标记为采集完成，第三方总量={}", monthId, monthThirdPartyTotal);
    }

    @Override
    public DiSyncSnapshot getLatestSnapshot(String sourceProjectNum, String dataType) {
        return lambdaQuery()
                .eq(DiSyncSnapshot::getSourceProjectNum, sourceProjectNum)
                .eq(DiSyncSnapshot::getDataType, dataType)
                .isNull(DiSyncSnapshot::getMonthId)
                .orderByDesc(DiSyncSnapshot::getSyncTime)
                .last("LIMIT 1")
                .one();
    }
}
