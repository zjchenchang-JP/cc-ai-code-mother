package com.zjcc.ccaicodemother.ai;

import com.zjcc.ccaicodemother.ai.model.HtmlCodeResult;
import com.zjcc.ccaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 结构化输出:
 * 直接返回AI生成的字符串的结果，不便于后续解析代码并保存为文件。
 * 因此需要将 AI 的输出转换为结构化的对象 HtmlCodeResult、MultiFileCodeResult
 */
public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     * 同步：等全部生成完一次性返回
     * @param userMessage 用户提示词
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(String userMessage);

    /**
     * 生成多文件代码
     * @param userMessage 用户提示词
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(String userMessage);

    /**
     * 生成 HTML 代码
     * 流式输出返回的是字符串片段
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(String userMessage);

    /**
     * 生成多文件代码
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(String userMessage);

    /**
     * 生成 Vue 项目代码（流式）
     * 工具调用 memoryId 必须也给方法参数补上 memoryId，而且有了 memoryId 就必须指定 memoryProvider
     * @param userMessage 用户消息
     * @return 生成过程的流式响应
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    Flux<String> generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String userMessage);


}
