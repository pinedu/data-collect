package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.mapper.DiSyncSnapshotMapper;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
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
        // 查询今天是否已有同一项目+数据类型的记录，有则更新，无则新增
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        DiSyncSnapshot exist = lambdaQuery()
                .eq(DiSyncSnapshot::getSourceProjectNum, sourceProjectNum)
                .eq(DiSyncSnapshot::getDataType, dataType)
                .ge(DiSyncSnapshot::getSyncTime, todayStart)
                .lt(DiSyncSnapshot::getSyncTime, todayEnd)
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
            save(snapshot);
            log.info("创建同步快照: 项目={}, 类型={}, 第三方={}, 本地={}",
                    sourceProjectNum, dataType, thirdPartyTotal, localTotal);
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
            save(snapshot);
            log.info("创建月份快照: 项目={}, 类型={}, 月份={}, syncDate={}, thirdTotal={}",
                    sourceProjectNum, dataType, monthId, monthSyncDate, monthThirdPartyTotal);
        }
    }
}
