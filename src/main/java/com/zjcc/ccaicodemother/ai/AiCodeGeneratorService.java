package com.zjcc.ccaicodemother.ai;

import com.zjcc.ccaicodemother.ai.model.HtmlCodeResult;
import com.zjcc.ccaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.SystemMessage;

/**
 * 结构化输出:
 * 直接返回AI生成的字符串的结果，不便于后续解析代码并保存为文件。
 * 因此需要将 AI 的输出转换为结构化的对象 HtmlCodeResult、MultiFileCodeResult
 */
public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(String userMessage);

    /**
     * 生成多文件代码
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(String userMessage);
}
