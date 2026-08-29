# 2026/08/29

## 一、core 包设计模式重构：新旧方案结构对照

```
旧方案（工具类 + 静态方法）                  新方案（设计模式分层）
core/                                       core/
├── CodeParser.java      ← 一个大类          ├── parser/          【策略模式】
│   parseHtmlCode()      静态方法            │   ├── CodeParser<T>          接口（泛型返回）
│   parseMultiFileCode()                    │   ├── HtmlCodeParser         实现
├── CodeFileSaver.java   ← 一个大类          │   ├── MultiFileCodeParser    实现
│   saveHtmlCodeResult()                    │   └── CodeParserExecutor     按类型分发
│   saveMultiFileCodeResult()               ├── save/            【模板方法模式】
└── AiCodeGeneratorFacade                   │   ├── CodeFileSaverTemplate<T>  抽象模板
    └── 4 个私有方法                        │   ├── HtmlCodeFileSaverTemplate
        （同步×2 + 流式×2）                  │   ├── MultiFileCodeFileSaverTemplate
        几乎复制粘贴                         │   └── CodeFileSaverExecutor     按类型分发
                                            └── AiCodeGeneratorFacade
                                                └── processCodeStream() 通用流式处理
```

**本质**：旧方案是"按模式横向复制"（每加一种模式，把流程抄一遍）；新方案是"按职责纵向分层"（流程骨架只有一份，模式只填差异）。**扩展 = 加文件，修改 = 改一处**。

## 二、解析器 → 策略模式

把"两种解析算法"从一个类里的两个方法，变成两个平等的实现类；接口 `CodeParser<T>` 用泛型统一"输入 String、输出结果对象"的契约：

```
CodeParser<T>（接口）
     ↑ 实现                    ↑ 实现
HtmlCodeParser            MultiFileCodeParser
（返回 HtmlCodeResult）    （返回 MultiFileCodeResult）

CodeParserExecutor.executeParser(content, type)
     └── switch 分发到对应策略，只管"选谁"，不关心"怎么解析"
```

## 三、保存器 → 模板方法模式

`CodeFileSaverTemplate.saveCode()` 是 **final 模板方法**，把保存的固定流程锁死在父类：

```java
public final File saveCode(T result) {
    validateInput(result);                  // ① 校验（钩子：子类可覆盖加强）
    String baseDirPath = buildUniqueDir();  // ② 建唯一目录（final：公共逻辑）
    saveFiles(result, baseDirPath);         // ③ 写文件（抽象：子类决定写几个）
    return new File(baseDirPath);           // ④ 返回目录
}
```

**父类管不变，子类管可变**：

| 流程步骤 | 谁负责 | 为什么 |
|---|---|---|
| 建目录（雪花ID、mkdir） | 父类 `final` | 所有保存器一样，不许子类瞎改 |
| writeToFile 工具 | 父类 `final` | 公共能力统一维护 |
| 基础校验（null 检查） | 父类默认实现 | 公共底线 |
| **写哪些文件** | 子类 `saveFiles()` 抽象 | HTML 写 1 个、多文件写 3 个——真正差异化的部分 |
| **怎么加强校验** | 子类覆盖 `validateInput()` 钩子 | 各模式校验规则不同 |

**防御升级（对比旧方案）**：
- 校验从 Facade 挪进模板的钩子：`MultiFileCodeFileSaverTemplate` 只要求 `htmlCode` 非空（css/js 允许为空——AI 生成纯 HTML 页没有 CSS/JS 是正常的，比"三字段全非空"更合理）；
- `writeToFile` 加了 `isNotBlank` 检查，**空字段直接不写文件**——根治了此前追踪的 `PrintWriter.print(null)` 写出字面量 "null" 文件的问题。

## 四、门面收敛：processCodeStream 消灭复制粘贴

旧 Facade 的两个流式私有方法"收集→解析→保存"骨架完全一样，只是调用的方法不同。新方案抽成一份：

```java
private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType) {
    StringBuilder codeBuilder = new StringBuilder();
    return codeStream
        .doOnNext(codeBuilder::append)
        .doOnComplete(() -> {
            String completeCode = codeBuilder.toString();
            Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);  // 分发解析
            File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType);      // 分发保存
        });
}
```

类型差异被 `codeGenType` 参数 + 两个执行器吃掉，一份骨架服务所有模式。

## 五、场景对比：优化什么时候兑现价值

### 场景 A：新增 REACT 生成模式（扩展）

**旧方案要动 6 处**，其中 2 处是复制粘贴：

```java
// ① CodeParser 大类再加静态方法 parseReactCode()（类继续膨胀）
// ② CodeFileSaver 大类再加 saveReactCodeResult()（第 3 遍复制建目录/写文件逻辑）
// ③④ Facade 加 generateAndSaveReactCode() + generateAndSaveReactCodeStream()
//    ——后者要把 40 行 doOnNext/doOnComplete 骨架【再抄一遍】
// ⑤⑥ 两个 switch 各加一个 case
```

**新方案 = 3 个新文件 + 2 处注册，零复制**：

```java
// 新文件1：只写"怎么解析"
public class ReactCodeParser implements CodeParser<ReactCodeResult> {
    public ReactCodeResult parseCode(String codeContent) { /* 解析 JSX 代码块 */ }
}

// 新文件2：只写"和别人不一样的部分"
public class ReactCodeFileSaverTemplate extends CodeFileSaverTemplate<ReactCodeResult> {
    protected CodeGenTypeEnum getCodeType() { return CodeGenTypeEnum.REACT; }
    protected void saveFiles(ReactCodeResult result, String dir) {
        writeToFile(dir, "App.jsx", result.getJsxCode());       // 建目录/写文件/流程全复用父类
        writeToFile(dir, "package.json", result.getPackageJson());
    }
}

// 修改1+2：两个 Executor 各注册一行 + switch 加 case
```

且删掉 Executor 的 default 分支后，新增 `REACT` 枚举值时**编译器强制**两个 switch 补 case（穷尽性检查），漏一处编译不过；旧方案全靠人肉记忆，漏了是运行时 bug。

### 场景 B：修改公共规则（修改）

需求："所有生成文件的目录改成按用户 ID 分目录"。

- **旧方案**：`saveHtmlCodeResult` 和 `saveMultiFileCodeResult` 各有一份 `buildUniqueDir`，N 种模式 = N 处改，漏一处行为不一致；
- **新方案**：只改 `CodeFileSaverTemplate.buildUniqueDir()` 一个方法，所有子类瞬间生效。writeToFile 想加备份/改编码同理。

## 六、trade-off 与遗留清理

1. **类型安全的牺牲**：`CodeParserExecutor.executeParser` 返回 `Object`，跨执行器传递时丢失编译期检查，内部靠 `(HtmlCodeResult)` 强转 + case 对应关系保证正确。这是"统一分发出口"的常见妥协；更严格的写法是 `<T> T execute(...)` 泛型方法，或 Facade 不经 Object 中转。
2. **死代码待清理**：旧文件 `core/CodeParser.java`、`core/CodeFileSaver.java` 仍在；Facade 里旧方案的 4 个私有方法已无调用——提交前删掉，避免新旧两套并存误导后续维护。

## 要点速查

| 主题 | 一句话结论 |
|---|---|
| 策略模式（parser） | 算法平等封装成类，执行器只管分发；加算法 = 加文件 |
| 模板方法（saver） | 不变流程 final 锁父类，差异点抽象给子类；改流程 = 改一处 |
| 门面收敛 | 流式骨架抽成 processCodeStream，类型差异交给执行器 |
| 扩展收益 | 新模式：旧 6 处（含复制）→ 新 3 文件 + 2 注册 |
| 防御升级 | validateInput 钩子 + writeToFile isNotBlank，根治 "null" 文件 |
