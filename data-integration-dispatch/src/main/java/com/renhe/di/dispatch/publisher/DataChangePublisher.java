package com.renhe.di.dispatch.publisher;

import com.renhe.di.api.vo.DataChangeEventVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据变更事件发布器
 */
@Slf4j
@Component
public class DataChangePublisher {

    private static final String EXCHANGE_NAME = "data.change.exchange";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发布数据变更事件
     *
     * @param dataType  数据类型
     * @param projectNum 项目编号
     * @param dataId    数据ID
     * @param action    操作类型 CREATE/UPDATE/DELETE
     */
    public void publish(String dataType, String projectNum, String dataId, String action) {
        publish(dataType, projectNum, dataId, action, null);
    }

    /**
     * 发布数据变更事件（携带数据）
     *
     * @param dataType  数据类型
     * @param projectNum 项目编号
     * @param dataId    数据ID
     * @param action    操作类型
     * @param data      数据内容
     */
    public void publish(String dataType, String projectNum, String dataId, String action, Object data) {
        DataChangeEventVO event = DataChangeEventVO.builder()
                .dataType(dataType)
                .projectNum(projectNum)
                .dataId(dataId)
                .action(action)
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();

        String routingKey = "data.change." + dataType.toLowerCase();
        try {
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, routingKey, event);
            log.debug("发布数据变更事件: {} {} {}", dataType, action, dataId);
        } catch (Exception e) {
            log.error("发布数据变更事件失败: {} {} {} - {}", dataType, action, dataId, e.getMessage());
        }
    }
}
