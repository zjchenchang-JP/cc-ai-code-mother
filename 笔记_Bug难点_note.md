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

# 2026/09/01

## 一、对话记忆四问：chat_history vs ChatMemory、MySQL vs Redis、窗口缺失与全面性保障

背景教程原文：*"目前的 AI 对话会断片儿，无法记住之前的对话内容，每次修改实际上都是重新生成完整的网站，而不是在原有基础上进行修改"*，以及 *"数据库中的对话历史表包含其他业务字段，不适合直接交给 LangChain4j 的对话记忆组件管理"*、*"只需要在初始化会话记忆时，加载最新的对话记录到 Redis 中，就能确保 AI 了解交互历史"*。

### 问题一：chat_history 表不就是记忆吗？为什么还要开发对话记忆功能？

**先纠正隐含假设："数据存下来了"不等于"AI 知道"**。回到 LLM 无状态这个根本事实：

```
每次调 AI，模型能看到的世界 = 本次请求的 messages 数组，仅此而已
它看不到你的 MySQL，看不到 Redis，看不到上周的对话
```

用这个标尺量两样东西：

| | chat_history 表（MySQL） | ChatMemory（对话记忆组件） |
|---|---|---|
| **服务谁** | 前端页面（渲染聊天记录、游标分页翻历史） | LangChain4j 的 AiServices（组装下一次请求） |
| **本质** | **存档**：数据躺着不动 | **供给**：每轮对话时被取出、注入 messages 数组 |
| **要求** | 永久保存、可按 appId/时间查询、带业务字段（userId/messageType...） | 读写快、**有窗口上限**（只保留最近 N 条）、格式是 ChatMessage 对象 |
| **写入模式** | append-only：只增不删，越全越好 | 滑动窗口：新消息顶掉旧消息，主动淘汰 |

**"JSON 格式通过 messageType 区分的聊天记录就是记忆？"——不是。** 那是**记忆的原始素材**。真正的"记忆"定义是：**下一次调 AI 时真正进入 messages 数组的那段内容**。日记本锁在抽屉里不是记忆，开口前翻出来的那几页才是。

教程说"目前 AI 对话会断片儿"的准确含义：**应用记得（chat_history 在存），但 AI 不记得（历史从没被拼进请求）**。当前 `chatToGenCode` 链路发给模型的只有本轮 `message`——这就是断片儿的技术表现。对话记忆功能要补的就是"取出→转换→注入"这条供给线。

**都必要吗？** 分层看：

- chat_history **必要**——前端聊天窗口要翻历史，这是唯一数据源；Redis 里的记忆丢了/过期了，还要靠它重建；
- ChatMemory 组件**不是绝对必要，是工程选择**——没有它也可以每次调 AI 前手动 `SELECT 最近N条 → 转成 UserMessage/AiMessage → 拼 messages`。组件的价值是把这套"取数+转换+窗口淘汰+跨请求缓存"的样板代码标准化，并接上 `AiServices.builder().chatMemoryProvider(...)` 流水线。

> 一句话：**chat_history 解决"记在哪"，ChatMemory 解决"每次说多少、怎么说给模型听"**。一个是仓库，一个是传菜口。

### 问题二：为什么不用 MySQL 存会话记忆？VO 遮蔽不行吗？

先看 LangChain4j 要什么。`ChatMemoryStore` 接口：

```java
public interface ChatMemoryStore {
    List<ChatMessage> getMessages(Object memoryId);                    // 按 key 整窗读
    void updateMessages(Object memoryId, List<ChatMessage> messages);  // 按 key 整窗【重写】
    void deleteMessages(Object memoryId);                              // 按 key 【删除】
}
```

它把存储当成 **`memoryId → 消息窗口` 的 KV 结构**，且**它说了算**：每轮对话后拿**完整窗口**调 `updateMessages`（重写而非追加），窗口滑动时淘汰旧消息（删除）。直接映射到业务表上有三个致命冲突：

**① 生命周期冲突（最致命）**：ChatMemory 窗口滑动时要删旧消息——直接映射就会**把业务表的历史记录物理删掉**。而 chat_history 的语义是 append-only 审计流（前端要无限往上翻）。同一张表，一个主人要"删旧"，一个主人要"保全"，必然精神分裂。

**② 类型不匹配**：接口要求 `List<ChatMessage>`（LangChain4j 的多态消息对象），不是 `ChatHistory` 实体。**这正是"VO 遮蔽"方案的盲区**：VO 能隐藏多余字段，但**变不出正确的类型**——照样要写一套 `ChatHistory行 ↔ ChatMessage` 双向转换器，转换器的 bug（messageType 映射错、内容序列化丢结构）就是新坑。遮字段是最浅的一层问题。

**③ 关注点污染**：业务表的 isDelete（逻辑删除）、游标分页等机制与 ChatMemory 的"窗口淘汰"两套删除语义搅在一起，排查是灾难。

**教程那句话的完整翻译**：不是"字段多了脏"，而是**所有权问题**——这张表属于业务域（前端展示），组件想要一个由它独占管理的 KV 窗口。分家（MySQL 管档案，Redis 管热窗口）后各自生命周期互不干扰。`RedisChatMemoryStoreConfig` 就是在 Redis 上实现这个 KV 接口。

### 问题三：为什么"加载最新对话记录"就够了？AI 上下文不还是缺的吗？

**直觉是对的：AI 的上下文确实缺了——但这是精心设计的"故意缺"**，三个层次：

**① 窗口本来就是有限的**。标准实现 `MessageWindowChatMemory(maxMessages=N)`（如 10~20 条）或按 token 截断。原因很硬：模型上下文有上限、token 按量计费、塞太长还会稀释注意力降低质量。**"全部历史塞进上下文"在工程上从来不是选项**——ChatGPT 也会忘，同一个原因。

**② 对"改网站"场景，最新窗口恰好携带了几乎全部有效信息**。迭代式代码修改的依赖链：

```
决定下一版网站的 = 最新一版完整代码（在最近一条 AI 消息里）+ 用户最新指令
20 轮之前的"把标题改成蓝色"早已被后续版本覆盖，丢了几乎不损失信息
```

老对话不是没价值，而是**被后续版本"取代"了**——改文档不需要第 1 稿到第 18 稿的全部 diff，只需要当前稿 + 最新批注。

**③ MySQL 是全量档案，Redis 是窗口的物化缓存**。准确流程：

```
对话请求进来
   ↓
ChatMemory.getMessages(appId)   ← memoryId 就是 appId
   ↓
Redis 里有窗口？──有──→ 直接用（快，热路径）
   │
   没有（首次/过期/重启）
   ↓
从 MySQL SELECT 最新 N 条（不是全部！）→ 转成 ChatMessage → 写入 Redis → 供本次及后续使用
```

"最新对话记录"的**最新 = 窗口容量以内的最新 N 条**。更老的行没有遗忘——它们仍在为前端翻页服务，只是**不再进入 AI 的视野**，这是成本与质量的权衡旋钮。真在意老上下文的进阶做法：eviction 监听器把被挤出的消息**压缩成摘要**注入 system message（"记不清细节但记得大概"）。

### 问题四（追问）：如果最新 N 条只是某问题的局部讨论，用户接着问，怎么保障 AI 回答全面？

**诚实的答案：保障不了绝对的全面——滑动窗口本质上是有损压缩**。工程上不追求"永不丢失"，追求三层：**让丢失大概率不发生、发生了有补救、补救不了能优雅降级**。

**本项目"窗口 + 最新代码"覆盖大部分场景的底气**：

```
窗口里最近一条 AI 消息 = 完整的最新代码
                                 ↑
这份代码是【所有历史决策的物化沉淀】
```

用户 20 轮前说"加个搜索框"——不管 AI 记不记得那句话，**代码里搜索框已经在了**。**把记忆物化成状态（代码/数据库），比记住过程（对话）可靠得多**——对话可以断片，状态不会。这是头号工程手段。

**真正的真空区**：信息只在对话里存在过、没沉淀进代码/状态的**纯过程性记忆**（如第 5 轮的长篇需求描述被第 6-30 轮挤出窗口，第 31 轮用户说"把我刚才说的加上"）。对付它的工程手段金字塔：

| 层级 | 手段 | 原理 | 代价 |
|---|---|---|---|
| 1️⃣ 调大窗口 | `MessageWindowChatMemory(maxMessages=50)` | 最笨最有效，直接少丢 | token 费用上涨、注意力稀释、延迟增加 |
| 2️⃣ 摘要压缩 | eviction 监听器：消息被挤出时**调一次 AI 压缩成摘要**，注入 system message | "记不清细节但记得大概" | 每次淘汰多一次 AI 调用；摘要有损 |
| 3️⃣ 检索召回（RAG） | 历史对话**向量化**存 EmbeddingStore，提问时**按语义检索**相关片段拼进上下文 | 不按"新旧"保留，按"相关性"召回 | 架构复杂度大增（教程 RAG/知识库章节） |
| 4️⃣ 状态外置 | 关键信息物化到结构化存储（代码、app 表） | 记"结果"不记"过程" | 需要业务建模配合 |

成熟产品都是叠着用的：ChatGPT 的记忆 = 窗口 + 自动摘要 + 长期记忆抽取；企业客服 = 窗口 + RAG 知识库 + 工单库。

**最后一级：优雅降级（产品层兜底）**。所有手段用上仍有信息丢失的场景，唯一正确行为是**让 AI 知道自己不知道**：

- system prompt 写明："如果用户引用了你上下文中不存在的历史内容，请明确说明你不确定，并请用户重述，而不是猜测"；
- 失败对比：**失败A（幻觉）**：AI 凭关键词脑补一套规则 → 生成错误代码 → 用户以为系统坏了；**失败B（承认）**：AI 说"能再说一次你的需求吗" → 用户重述 → 正确完成。窗口架构的可用性标准：**宁可 B，绝不 A**。

**决策图**：

```
信息会丢失吗？→ 会（有损压缩，先接受）
     ↓
能不能物化成状态？（代码/DB）──能──→ 小窗口就够（本项目的选择）★
     ↓ 不能（纯对话性信息）
调大窗口够不够？──够──→ 调大即可
     ↓ 不够
加摘要压缩（记住大概）
     ↓ 还不够
上 RAG 按需召回（记住相关）
     ↓ 兜底
prompt 约定"不知道就问"（诚实退化）
```

### 总图收束

```
                    ┌─ 前端聊天窗口（翻历史、游标分页）
MySQL chat_history ─┤   全量档案 · append-only · 永不删
（完整对话 + 业务字段）│
                    └─ 冷启动时重建 Redis 窗口（取最新 N 条）

Redis ChatMemory ────→ 每轮注入 AI 请求的 messages
    滑动窗口 · 热缓存 · 可丢可重建          ↑
                                          │
用户本轮新消息 ────────────────────────────┘
```

**一句话**：保障"全面"的正确姿势不是无限记忆，而是——**重要的变成状态，最近的留在窗口，大概的留成摘要，相关的按需检索，剩下的诚实承认不知道**。本项目选择了"窗口 + 代码物化"的性价比组合，天然覆盖迭代改码场景；要服务"引用很久以前讨论"的重对话产品，再往金字塔上层加码。

---

## 二、Redis 对话记忆排障实录：NOAUTH → wrong number of arguments → 根治

### 现象与首案发现场

接入 RedisChatMemoryStore 后聊天报错，日志链：

```
INSERT chat_history（用户消息落 MySQL）        ✅
SELECT chat_history LIMIT 1,20（loadChatHistoryToMemory 查历史） ✅
chatMemory.clear() → Redis                    ❌ NOAUTH Authentication required
   → 被 loadChatHistoryToMemory 的 catch 吞掉，日志"加载历史对话失败"
   → 代理照常构建返回
case MULTI_FILE → generateMultiFileCodeStream → DefaultAiServices 内部 chatMemory.add(用户消息) → Redis
   → 第二个 NOAUTH，无人捕获，沿 Flux 抛出    ← 断点在 MULTI_FILE case 看到的就是它
```

**关键排除法**：Spring Session（Lettuce 客户端）用同一个 yml 密码登录态一直正常 → **密码本身没错、yml 没错** → 嫌疑锁定在 `RedisChatMemoryStore` 这条独立的 Jedis 连接。

### 证据收集：反汇编 jar（javap）

配置代码"看起来对"（host/port/password/ttl 都传了），肉眼已无法推进——直接反汇编依赖 jar：

```
javap -p -c langchain4j-community-redis-1.1.0-beta7.jar 里的 RedisChatMemoryStore
```

构造器的真实分支逻辑（字节码翻译）：

```java
if (user != null) {                                    // ★ 判断的是 user，不是 password！
    ensureNotBlank(user);
    ensureNotBlank(password);
    client = new JedisPooled(host, port, user, password);   // 带认证
} else {
    client = new JedisPooled(host, port);              // ★ 裸连——password 被静默丢弃！
}
```

**根因一**：只传 `.password(...)` 不传 `.user(...)` → user 为 null → 走裸连分支 → 传了的密码根本没进 Jedis → `NOAUTH`（客户端压根没发认证命令）。

Builder 方法列表也一并确认：只有 host/port/user/password/ttl/prefix 六个参数，**没有客户端注入口**，代码层绕不开这个分支。

### 第一次修复与新错误

加 `.user("default")`（Redis 6+ 内置默认用户）后，错误变成：

```
ERR wrong number of arguments for 'auth' command
```

**根因二**：设了 user 后 Jedis 发送的是**两参数 AUTH**（`AUTH default 123456`，用户名+密码形式）——这是 **Redis 6.0 才引入的 ACL 特性**。本机 Windows Redis（老移植版，5.x/3.x）只认单参数 `AUTH 密码` → 报"参数个数不对"。

验证命令：

```
redis-cli INFO server | findstr redis_version    → 确认版本 < 6.0
redis-cli AUTH default 123456                    → 复现一模一样的错误
```

### 死局与出路

这个组件版本下的两头堵：

| 配置 | 结果 |
|---|---|
| 不设 user | password 被静默忽略 → NOAUTH |
| 设 user | 强制两参数 AUTH → 老 Redis 不认 → wrong number of arguments |

出路（按推荐排序）：①本地 Redis 去掉 requirepass（最终采用）；②升级 Redis 到 6+/7+（Docker/Memurai/WSL2）；③升级 community-redis 依赖到与 core 匹配的新 beta（可能已修复，未验证）；④自实现 ChatMemoryStore 接口自建连接（30 行兜底）。

### Windows Redis 服务方式的重启排障

采用方案①后，重启 Redis 又踩了 Windows 特有的坑：

```
tasklist | findstr -i redis
→ redis-server.exe  5736  Services  ...     ← Session 列 = Services，铁证：服务方式在跑

sc qc Redis
→ 空输出                                    ← 服务名不叫 "Redis"！
```

查真名 + 它实际加载的 conf（一条命令双杀）：

```powershell
Get-CimInstance Win32_Service -Filter "PathName LIKE '%redis%'" | Format-List Name, PathName
# PathName 里能看到 --service-run 指向的 conf 路径
```

**经典坑**：Windows 版 Redis 目录里有三个 conf——`redis.conf`、`redis.windows.conf`、`redis.windows-service.conf`。**服务方式默认用最后一个**；改了前两个等于白改（`ping` 依旧 NOAUTH 就说明 conf 没改对）。

正确流程：在 PathName 指向的 conf 里注释 `requirepass` → `Restart-Service <真名>` → 验证：

```
redis-cli
127.0.0.1:6379> ping
PONG                    ← 不输密码直接通 = 生效 ✅
```

**最后收尾（容易漏）**：服务端去掉密码后，Java 侧必须同步删——yml 删 `password`、Config 删 `.user(...)`/`.password(...)`，否则反向翻车（客户端往无密码服务器发 AUTH，报 `ERR Client sent AUTH, but no password is set`，Spring Session 直接挂）。

### 经验沉淀

1. **builder 参数"传了却静默不生效"是最阴的坑**——编译不报错、运行不警告，肉眼永远查不出来。终极手段：反汇编 jar 看分支条件（`javap -p -c`，从本地 Maven 仓库直接解包）
2. **Redis 认证错误的三种形态要会读**：`NOAUTH` = 客户端没发认证（密码没传到）；`WRONGPASS` = 密码错；`wrong number of arguments for 'auth'` = 发了两参数 AUTH 给老版本（<6.0）
3. **两参数 AUTH 是 Redis 6.0 ACL 特性**——遇到"设了用户名反而报错"，先查服务端版本
4. **同一台 Redis 两个客户端行为可以不同**：Lettuce（Spring）没设用户名只发单参数 AUTH 所以一直正常，Jedis 设了 user 就发双参数——排障时"Spring 的 Redis 好的"不能证明"所有客户端都好的"
5. **Windows 服务方式三连查**：`tasklist`（Session 列判断是否服务）→ `Get-CimInstance Win32_Service`（查真名 + 实际 conf 路径）→ 改对 conf 再 `Restart-Service`
6. **改密码是双向同步**：服务端和所有客户端配置要一起动，只改一边必然出现新错误

# 2026/09/02

## 一、双 StreamingChatModel Bean 冲突：NoUniqueBeanDefinitionException 排障实录

### 现象

新增 Vue 项目生成功能（`ReasoningStreamingChatModelConfig` + `FileWriteTool`）后，`generateVueProjectCodeStream` 单测失败：

```
IllegalStateException: Failed to load ApplicationContext for [...testClass = AiCodeGeneratorFacadeTest...]
```

**关键认知：这个异常 = Spring 容器在启动阶段就挂了，根本没走到测试方法体**——所以和"Vue 生成逻辑对不对"毫无关系，任何 @SpringBootTest 测试都会一起全灭。

### 排查过程

**① 识别信息缺失**：贴出来的只有最外层 IllegalStateException，真正的死因在被截断的 `Caused by` 链里。

**② 复现抓根因的技巧——用"不花钱的负向用例"**：挑一个会加载完整容器但**不调 AI** 的测试方法（`generateAndSaveCodeRejectsNullType`，参数校验直接抛异常）跑 mvn test：

```bash
mvn test -Dtest='AiCodeGeneratorFacadeTest#generateAndSaveCodeRejectsNullType'
```

容器加载失败会在 surefire 输出里吐出完整 Caused by 链；如果容器能起，也只是跑一个毫秒级校验用例，零 API 消耗。

**③ 根因现形**：

```
Caused by: NoUniqueBeanDefinitionException:
No qualifying bean of type 'dev.langchain4j.model.chat.StreamingChatModel' available:
expected single matching bean but found 2: reasoningStreamingChatModel, openAiStreamingChatModel
```

### 原因解析

**容器里出现了两个同类型 Bean**：

```
① openAiStreamingChatModel    ← langchain4j starter 根据 yml streaming-chat-model 自动建（原有）
② reasoningStreamingChatModel ← 新加的 ReasoningStreamingChatModelConfig 手动建（Vue 生成用推理模型）
```

工厂里的两个注入字段，命运截然不同：

```java
@Resource
private StreamingChatModel streamingChatModel;          // ❌ 炸：字段名对不上任何 Bean 名

@Resource
private StreamingChatModel reasoningStreamingChatModel; // ✅ 没事：字段名恰好 = Bean 名②
```

**@Resource 的注入规则是"先按名字，名字对不上才退化为按类型"**：

- 字段 `reasoningStreamingChatModel` 按**名字**精准命中 Bean ②，根本不走类型匹配 → 安全；
- 字段 `streamingChatModel` 和两个 Bean 名都不匹配 → 退化为**按类型** → 发现两个候选 → 无法裁决 → `NoUniqueBeanDefinitionException` → 工厂 Bean 创建失败 → 容器启动失败。

**为什么以前没事**：此前容器里只有一个 StreamingChatModel，按类型注入"唯一命中"的隐含前提成立；第二个 Bean 一加入，唯一性被打破，按类型注入立即现原形。

### 修复（一行，按推荐排序）

```java
// 写法一：显式指定 Bean 名（最清晰）
@Resource(name = "openAiStreamingChatModel")
private StreamingChatModel streamingChatModel;

// 写法二：字段名直接对上 Bean 名（名字即路由）
@Resource
private StreamingChatModel openAiStreamingChatModel;
```

**不推荐 @Primary**：starter 自动建的 Bean 够不着加注解；给自己的 reasoning Bean 标 @Primary 会让"普通流式"错拿到推理模型，语义反转。多模型并存时，**每个注入点显式指名道姓**才是正解。

### 经验沉淀

1. **Failed to load ApplicationContext = 容器启动失败 ≠ 测试方法逻辑错**——排查方向是 Bean 装配（新加的 @Configuration/@Bean），不要在测试代码里找 bug
2. **只贴最外层异常等于没贴**——真正的死因永远在最后一个 `Caused by`；IDEA 里点击异常链跳到最底部那个
3. **复现容器启动失败用"负向用例"**——选一个不触发外部调用的测试方法跑，既能完整加载容器暴露装配错误，又不花 API 钱
4. **@Resource vs @Autowired 的语义差异**：@Resource 先按名字再按类型（jakarta 标准）；@Autowired 先按类型，配 @Qualifier 指名——多 Bean 场景两种都要显式指名
5. **按类型注入靠"同类型唯一"活着**——引入第二个同类型 Bean（多模型架构、多数据源、多缓存）的那一刻，全项目所有裸 @Resource/@Autowired 注入点都要重新审视
6. 呼应 langchain4j 官方文档的预言：*"automatic wiring fails to start the app if duplicate components of one type exist"*——文档早已警告，落地验证

---

## 二、toolExecutionRequests 为空：模型"假装"调用工具排障实录

### 现象（从 Redis 记忆 JSON 里发现）

Vue 项目生成的对话记忆（Redis）里，AI 消息的 `toolExecutionRequests: []` 为空，且 AI 的回复文本是这种形态：

```
【文件写入工具】package.json
```json
{ ... }
```

【文件写入工具】src/App.vue
```vue
...
```
```

——**模型在文本里"角色扮演"工具调用，从头到尾没有发起过一次真实的 function call**。系统提示词明确要求"必须通过【文件写入工具】创建每个文件"，但没有任何工具被执行，`tmp/code_output/vue_project_{appId}/` 下也没有产物文件。

### 证据解读：三个关键认知

1. **`toolExecutionRequests` 的含义**：AiMessage 里记录"这条回复中模型发起的工具调用"。空 = 模型从未发起。
2. **记忆 JSON 里没有 `TOOL_EXECUTION_RESULT` 类型消息**：如果发生过哪怕一次工具往返，窗口里必然出现工具结果消息——没有，佐证"从未调用"而非"调用了没记录"。
3. **"文本角色扮演工具调用"是标准病症**：模型**不能凭空发起 function call**——API 请求体里没有 `tools` 数组时，模型无论提示词怎么命令它用工具，都只能用文字模仿。看到假装调用，第一反应不该是查提示词，而是**查这次请求到底挂没挂工具**。

### 排查过程（自下而上四步）

**① 工具注册了吗？** 读工厂——`case VUE_PROJECT -> ...tools(new FileWriteTool())` ✓ 注册了，还有幻觉工具名兜底。

**② 提示词走对路了吗？** 记忆里 SYSTEM 是 Vue 专属提示词 ✓——说明调用的接口方法没错。

**③ 那实际拿到的实例对吗？**（破案点）读 Facade:

```java
// AiCodeGeneratorFacade.java:74（流式入口）
AiCodeGeneratorService aiCodeGeneratorService =
        aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId);   // ← 只传了 appId！
```

再看工厂的一参重载：

```java
public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
    return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);   // ← 写死 HTML
}
```

**④ HTML 分支的 builder 没有 `.tools()`**——事故链闭环：

```
测试调 generateAndSaveCodeStream(msg, VUE_PROJECT, appId)
   ↓
Facade:74 取实例只传 appId → 工厂返回【HTML 版】实例（无工具、默认模型）
   ↓
case VUE_PROJECT → 在"无工具实例"上调 generateVueProjectCodeStream()
   ↓
方法上挂着 Vue 提示词 → SYSTEM 是 Vue 提示词（造成"走了 Vue 路径"的假象）
   ↓
发给 DeepSeek 的请求体里没有 tools 数组
   ↓
模型服从提示词的唯一方式：文本假装调用 → toolExecutionRequests 永远为空
```

### 修复

Facade 两处（流式 74 行、同步 46 行）把类型传透：

```java
AiCodeGeneratorService aiCodeGeneratorService =
        aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
```

### 修复后验证三件套

1. **请求日志**（模型配了 logRequests(true)，工具调用的"照妖镜"）：请求体应出现 `"tools":[{...writeFile...}]` 数组
2. **产物**：`tmp/code_output/vue_project_{appId}/` 下出现真实文件
3. **记忆**：AI 消息 `toolExecutionRequests` 非空，窗口出现 `TOOL_EXECUTION_RESULT` 消息

### 顺带发现的两个遗留问题

1. **`Facade.java:86`**：`case VUE_PROJECT` 收尾 `processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId)`——Vue 产物由工具直接落盘，这条"解析+保存"老管线对它无意义，还会用 MULTI_FILE 解析器解析"生成计划"文本、在 `tmp/code_output/` 建垃圾目录
2. **memoryId 冲突隐患**：缓存键是 `appId_type`（实例隔离），但记忆 id 都是 `appId`——同 app 若换类型，两套服务共用一个记忆窗口互相污染。目前 app 类型创建后固定，暂无实害

### 经验沉淀

1. **模型"文本假装调工具" = 请求里没挂 tools 的标准信号**——先查服务实例构建，别改提示词
2. **一参便捷重载是静默降级的高危点**："同一接口、多个实例、能力不同"的架构里，兼容旧代码的一参重载（写死默认类型）会让忘传参的新调用点**不报错地拿到错误配置**——宁可让重载过期删除，或让它直接抛异常
3. **排障路径**：AI 行为异常（现象）→ 中间件证据（记忆/日志）→ 服务端配置（工厂注册）→ 调用方（Facade 传参）——本次 bug 的病灶在最后一环，前两环都是"看起来正常"的假象
4. **@SystemMessage 挂在接口方法上，所有实例共享**——"提示词对"不能证明"实例对"，能力差异（tools/模型）在 builder 里，不在方法上





---


