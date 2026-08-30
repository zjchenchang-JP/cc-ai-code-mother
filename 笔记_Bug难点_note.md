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

---

## 七、chatToGenCode 业务流程三问：权限校验时机、appId 来源、上下文依赖

### 真实业务流程全景图

```
① 用户点"创建应用"
   填：应用名 + 初始 prompt（"做一个任务记录网站"）+ 生成类型
   ↓
   POST /app/add  →  写入 app 表一条记录（此时只有元数据，没有代码）
   ↓
   返回 appId  ←★★★ appId 从这来，前端存住
   ↓
② 前端立刻带着 appId 调对话生成接口（首条消息就是 initPrompt）
   GET /app/chat/gen/code?appId=123&message=做一个任务记录网站
   ↓
   后端先做权限校验（这个 app 是不是你的？）→ 再流式调 AI
   ↓
   前端打字机效果逐字显示；同时写入 chat_history
   ↓
   生成完成 → 解析 → 落盘（生成 deployKey）→ 更新 app 表
   ↓
③ 用户继续对话迭代："把标题改成红色"
   再次 GET /app/chat/gen/code?appId=123&message=把标题改成红色
   （AI 结合 chat_history 里的历史 → 改出完整新版本 → 重新部署）
```

### 问题一：代码都没生成，为什么先权限校验？

权限校验保护的不是"代码"（还没生成），而是 **app 这条记录本身**。

`chatToGenCode(appId, ...)` 以 **appId 为操作对象**。不校验的话，用户 B 可以随便填一个 appId（遍历 id 就行）：

- **烧掉用户 A 的 AI 额度**（生成一次就是一次真实计费调用）；
- **污染 A 的 chat_history**（对话历史挂在 app 下，chat_history 表有 appId 字段）；
- **把 A 的应用覆盖成 B 想要的东西**。

所以校验必须发生在**任何生成动作之前**——先确认"这个 app 归你"，再花钱调 AI。与 `getAppById` 的"仅本人或管理员可查看"同一套逻辑，只是这次还会产生**写操作和消费**，校验更重要。

### 问题二：appId 从哪来？"空 app"是什么用户体验？为什么拆两步？

**创建和生成是两个接口、两步操作**：

- `POST /app/add` 负责创建，**返回值就是新 app 的 id**（Controller 里 `BaseResponse<Long>`）；
- 前端拿到 id 存起来，之后所有对话请求都带着它。

**此处 app = 容器/项目，还不是生成的成品网站。生成 = 往容器里灌内容**。类比：CodePen 先创建空 Pen 再编辑、GitHub 先建空仓库再 push。**用户感知不到"空 app"中间态**：前端"创建应用"表单提交 → `add` 返回 id → 自动跳转对话工作台 → **自动**把 initPrompt 作为第一条消息发给 chat/gen/code → 打字机流式输出。两步接口被前端串成一次体验。
>本项目：先建 app（名字+初始想法）→ 进对话工作台 → AI 流式生成 → 部署出访问地址

**不合成一个请求的理由**：

1. **多轮迭代需要实体挂载**：用户第一版不满意会说“改下标题”—— chat_history 挂在 app 上、codeGenType/deployKey/priority 都存在 app 行里；没有 app 实体，第二轮对话就没了上下文（ chat_history 表设计 idx_appId_createTime 游标索引就是为多轮准备的）
2. **快慢分离**：add 是毫秒级写库（立即反馈"创建成功"），生成是几十秒流式长连接，合并后失败处理(比如，超时)变复杂；
3. **"我的应用"管理**：用户要看到自己建过的所有应用列表、删除、改名都需要 app 作为独立实体；
4. **生成失败可重试**：AI 挂了重发 chat 请求即可，不会重复建 app。

### 问题三：为什么没有 app 实体，第二轮对话就没了上下文？上下文指什么？

**根本事实：LLM 是无状态的（stateless）**。每次调用 API，模型都当成初次见面——上次生成过什么、说过什么一概不记得。"它记得我们的对话"**完全是应用层拼装出来的**：每次请求前把历史消息从数据库捞出来、连同新消息一起塞进请求数组发给模型。

**上下文 = 每次调用时要重新喂给模型的两块东西**：

1. **对话历史**（chat_history 表）：用户说过什么、AI 答过什么；
2. **当前应用的代码版本**（app 关联的部署产物）：现在这个网站长什么样。

用户第二轮说"把标题改成红色"到达时，后端实际发给模型的 messages 数组：

```json
[
  {"role": "system",    "content": "你是前端专家...修改要求..."},
  {"role": "user",      "content": "做一个任务记录网站"},         ← 第1轮用户消息（查 chat_history）
  {"role": "assistant", "content": "```html <上一版完整代码> ```"}, ← 第1轮AI回复（查 chat_history）
  {"role": "user",      "content": "把标题改成红色"}              ← 本轮新消息
]
```

有了这些，"把标题改成红色"才是完整指令；没有这些，这七个字是**无头悬案**——什么标题？哪个网站？模型只能反问或凭空生成随机新页面。

**上下文全靠 appId 锚定**：

```
app 实体（appId）
 ├── chat_history 表：WHERE appId = ? ORDER BY createTime   ← 对话历史按 appId 分组挂载
 │   （建表 SQL 里 idx_appId_createTime 游标索引就是为这个查询准备的）
 └── 当前代码版本：app 行记录的部署产物                        ← 代码按 app 归属
```

没有 app 实体 = 没有锚点 = 第二轮请求到达时**没有任何键能捞出这两块内容**，对话退化成互不相识的单轮问答。

类比：**appId 就是聊天群的群号**——消息必须挂在群里才构成"一场持续的对话"，没有群号，"把昨天那个方案改一下"没人知道指哪次。

这也解释了 `chatToGenCode` 的签名 `(appId, message, loginUser)` 三件套：`appId` 圈定上下文范围、`message` 是本轮新增、`loginUser` 确认有权使用这个上下文。

### 附：produces = MediaType.TEXT_EVENT_STREAM_VALUE 的作用

```java
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatToGenCode(...)
```

`produces` 声明**接口产出什么类型的内容**——`text/event-stream` 是 **SSE（Server-Sent Events）协议的 MIME 类型**，作用有三：

1. **告诉 Spring 用 SSE 方式序列化 Flux**：返回的 `Flux<String>`（一堆未来陆续到达的片段） 每个片段被包成一个 SSE 帧实时推送（`data:xxx\n\n`），而不是等全部到齐拼成一个大响应。不声明的话 Flux 会被当普通响应体整体缓冲——流式白做了。**这是"流式"真正对浏览器生效的关键开关**；
```cookie
HTTP/1.1 200 OK
Content-Type: text/event-stream     ← produces 决定的

data:<!DOCTYPE      ← 第1个token到达就推一帧
                     （空行 = 帧分隔符）
data:<html>
                     
data:<head>任务记录
```
如果不声明，Spring 会把 Flux<String> 当普通响应体——要么整体缓冲成 JSON 数组（等到天荒地老才一次性返回，流式白做了），要么行为不符合预期。这个注解是“流式”真正对浏览器生效的关键开关
2. **内容协商**（Content Negotiation）：只匹配 `Accept: text/event-stream`（或 `*/*`）的请求，接口元数据进入 OpenAPI 文档；
3. **前端浏览器侧对接**：`text/event-stream` 是标准协议，前端用原生 `EventSource`（或 fetch 流式读取）逐帧收 `data:` 内容，不需要解析库。

**为什么用 SSE 不用 WebSocket**：生成代码只需要服务器单向推（用户输入走普通请求），SSE 是纯 HTTP、一个注解搞定；WebSocket 双向但要额外配置 handler。单向场景 SSE 刚好够用——ChatGPT 网页版同理。

---


