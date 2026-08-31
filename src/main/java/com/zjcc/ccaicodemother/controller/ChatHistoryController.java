package com.zjcc.ccaicodemother.controller;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import com.zjcc.ccaicodemother.service.ChatHistoryService;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话历史 控制层。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Resource
    private ChatHistoryService chatHistoryService;



}
