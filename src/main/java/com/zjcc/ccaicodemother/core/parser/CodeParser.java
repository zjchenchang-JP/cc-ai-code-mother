package com.zjcc.ccaicodemother.core.parser;

/**
 * 代码解析器策略接口
 * 代码优化 设计模式 - 策略模式
 * @author zjchenchang-JP
 */
public interface CodeParser<T> {

    /**
     * 解析 AI生成代码 内容
     * 通过泛型统一方法的返回值
     * @param codeContent 原始代码内容
     * @return 解析后的结果对象
     */
    T parseCode(String codeContent);
}
