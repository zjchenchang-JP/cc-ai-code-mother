package com.zjcc.ccaicodemother.service;

import com.mybatisflex.core.service.IService;
import com.zjcc.ccaicodemother.model.entity.ChatHistory;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加对话历史
     *
     * @param appId       应用 id
     * @param message     消息
     * @param messageType 消息类型
     * @param userId      用户 id
     * @return 是否成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 根据应用 id 删除对话历史
     *
     * @param appId
     * @return
     */
    boolean deleteByAppId(Long appId);
}
