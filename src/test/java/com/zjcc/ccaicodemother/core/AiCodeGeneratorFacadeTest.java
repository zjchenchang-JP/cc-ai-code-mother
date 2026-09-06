package com.zjcc.ccaicodemother.core;

import com.zjcc.ccaicodemother.exception.BusinessException;
import com.zjcc.ccaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新方案（策略模式 + 模板方法模式）的门面测试
 * 注意：除参数校验用例外，其余测试会真实调用 AI 接口，消耗额度
 *
 * @author zjchenchang
 * @createDate 2026/8/26 23:08
 */
@SpringBootTest
class AiCodeGeneratorFacadeTest {

    /**
     * AI 生成文件保存根目录，与 CodeFileSaverTemplate 中保持一致
     */
    private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCodeWithHtml() {
        File file = aiCodeGeneratorFacade.generateAndSaveCode("简易个人博客", CodeGenTypeEnum.HTML,1L);
        // 目录已创建
        Assertions.assertNotNull(file);
        Assertions.assertTrue(file.exists());
        Assertions.assertTrue(file.isDirectory());
        // 目录名以 html_ 开头，验证模板的 getCodeType() 生效
        Assertions.assertTrue(file.getName().startsWith("html_"));
        // 必须生成 index.html
        Assertions.assertTrue(new File(file, "index.html").exists());
    }

    @Test
    void generateAndSaveCodeWithMultiFile() {
        File file = aiCodeGeneratorFacade.generateAndSaveCode("任务记录网站", CodeGenTypeEnum.MULTI_FILE,1L);
        Assertions.assertNotNull(file);
        Assertions.assertTrue(file.exists());
        // 目录名以 multi_file_ 开头
        Assertions.assertTrue(file.getName().startsWith("multi_file_"));
        // 新方案校验规则：htmlCode 必填，css/js 可为空（为空则不写文件）
        Assertions.assertTrue(new File(file, "index.html").exists());
    }

    @Test
    void generateAndSaveCodeStreamWithHtml() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream("任务记录网站", CodeGenTypeEnum.HTML,1L);
        // 阻塞等待所有数据收集完成（doOnComplete 的保存动作先于 block() 返回执行）
        List<String> result = codeStream.collectList().block();
        Assertions.assertNotNull(result);
        // 拼接流式片段，得到完整内容
        String completeContent = String.join("", result);
        assertFalse(completeContent.isBlank(), "流式内容不应为空");
        // 流结束后应已落盘：保存根目录下存在 html_ 开头的目录
        Optional<File> savedDir = findLatestSavedDir("html_");
        Assertions.assertTrue(savedDir.isPresent(), "流式完成后应生成保存目录");
    }

    @Test
    void generateAndSaveCodeStreamWithMultiFile() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream("任务记录网站", CodeGenTypeEnum.MULTI_FILE,1L);
        // 阻塞等待所有数据收集完成（doOnComplete 的保存动作先于 block() 返回执行）
        List<String> result = codeStream.collectList().block();
        Assertions.assertNotNull(result);
        // 拼接流式片段，得到完整内容
        String completeContent = String.join("", result);
        assertFalse(completeContent.isBlank(), "流式内容不应为空");
        // 流结束后应已落盘：保存根目录下存在 html_ 开头的目录
        Optional<File> savedDir = findLatestSavedDir("html_");
        Assertions.assertTrue(savedDir.isPresent(), "流式完成后应生成保存目录");
    }

    @Test
    void generateAndSaveCodeRejectsNullType() {
        // 不消耗 AI 额度：类型为空直接被拦截
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiCodeGeneratorFacade.generateAndSaveCode("测试", null,1L));
        assertNotNull(exception.getMessage());
    }

    @Test
    void generateAndSaveCodeStreamRejectsNullType() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiCodeGeneratorFacade.generateAndSaveCodeStream("测试", null,1L));
        assertNotNull(exception.getMessage());
    }

    /**
     * 在保存根目录下查找指定前缀的目录
     *
     * @param prefix 目录名前缀（如 html_ / multi_file_）
     * @return 最新生成的目录
     */
    private Optional<File> findLatestSavedDir(String prefix) {
        File root = new File(FILE_SAVE_ROOT_DIR);
        File[] dirs = root.listFiles((dir, name) -> name.startsWith(prefix));
        if (dirs == null || dirs.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(dirs)
                .max(Comparator.comparingLong(File::lastModified));
    }

    @Test
    void generateVueProjectCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "简单的个人博客网站，总代码量不超过 200 行",
                CodeGenTypeEnum.VUE_PROJECT, 20260905L);
        // 阻塞等待所有数据收集完成
        List<String> result = codeStream.collectList().block();
        // 验证结果
        Assertions.assertNotNull(result);
        String completeContent = String.join("", result);
        Assertions.assertNotNull(completeContent);
    }

}
