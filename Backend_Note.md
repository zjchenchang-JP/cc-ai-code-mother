# 2026/08/24

## 一、knife4j 有响应，但 IDEA Console 没有日志？

**现象**：接口调用成功，knife4j 能看到响应，但 Console 只有启动日志，没有任何请求日志。

**原因**：这是 Spring Boot 的**默认行为**，不是故障。

- Tomcat / Spring MVC 处理正常请求时是**静默**的，成功请求不产生控制台输出。
- 项目里的 `GlobalExceptionHandler` 只在抛异常时才 `log.error`，所以日志规律是：**成功 = 无日志，业务异常 = 一段红字**。

### 解决：开启本地调试日志

在 `application-local.yml`（已被 gitignore，适合放本地调试配置）中添加：

```yaml
# 本地调试日志
logging:
  level:
    # 打印每个 HTTP 请求的调度日志
    org.springframework.web: debug
    # 打印 MyBatis Flex 实际执行的 SQL
    com.zjcc.ccaicodemother.mapper: debug
```

重启后调接口，Console 会输出每个请求的调度日志和**带参数的完整 SQL**，排查数据库问题非常有用。

---

## 二、日志解读：`parameters={}` 是什么意思？

**现象**：POST 请求明明带了 JSON 参数，日志却显示 `parameters={}`。

**结论**：正常。`DispatcherServlet` 日志里的 `parameters=` **只显示 URL 查询参数**（`?id=1&name=xx` 那种）。JSON 请求体的接收记录在另一行：

```
Read "application/json;charset=UTF-8" to [UserQueryRequest(id=null, userName=, ...)]
```

参数解析正常，只是记录位置不同。

### 一次请求的完整日志链（以 POST /api/user/list/page/vo 为例）

| 日志行 | 含义 |
|---|---|
| `POST "/api/user/list/page/vo", parameters={}` | 请求进入，URL 参数为空（JSON body 不在这里显示） |
| `Mapped to UserController#listUserVOByPage` | 路由匹配到 Controller 方法 |
| `Read "application/json..." to [UserQueryRequest(...)]` | JSON body 反序列化成对象 |
| `==> Preparing: SELECT ...` / `==> Parameters: ...` | MyBatis Flex 执行的 SQL 和参数 |
| `Writing [BaseResponse(code=0, ...)]` | 响应序列化 |
| `Completed 200 OK` | 请求结束 |

---

## 三、经典 Bug：空字符串变成查询条件，导致查询结果为空

**现象**：`/api/user/list/page/vo` 传全空字符串参数（`"userName": ""` 等），响应 `records: [], totalRow: 0`，一条数据都查不到。

**日志线索**：

```
WHERE (userRole = ? AND userAccount LIKE ? AND ...) AND isDelete = ?
Parameters: (String), %%(String), %%(String), %%(String), 0(Integer)
              ↑ 空字符串!
```

### 根因

- `getQueryWrapper` 把所有字段**无条件**拼进 QueryWrapper；
- `userRole = ''` 精确匹配空字符串，数据库里没有这种行 → **COUNT = 0 → 空页**。
- **MyBatis-Flex 只自动忽略 `null`，不忽略空字符串 `""`**——这是最容易踩的坑（`id=null` 没进 SQL，`userRole=""` 却进了）。

### 修复后的写法（每个条件非空才拼接）

```java
QueryWrapper queryWrapper = new QueryWrapper();
// id 为 null 时 MyBatis Flex 自动忽略该条件；空串则必须手动排除，否则会拼出 userRole = ''
queryWrapper.eq("id", id);
if (StrUtil.isNotBlank(userRole)) {
    queryWrapper.eq("userRole", userRole);
}
if (StrUtil.isNotBlank(userAccount)) {
    queryWrapper.like("userAccount", userAccount);
}
if (StrUtil.isNotBlank(userName)) {
    queryWrapper.like("userName", userName);
}
if (StrUtil.isNotBlank(userProfile)) {
    queryWrapper.like("userProfile", userProfile);
}
if (StrUtil.isNotBlank(sortField)) {
    queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
}
return queryWrapper;
```

**通用经验**：动态查询的每个条件都要问一句"为空时该怎样"——答案通常是"不参与查询"。

## 四、一次分页请求触发三条 SQL 的完整链路

```
POST /api/user/list/page/vo
   │
   ├─ ① @AuthCheck 切面拦截（Controller 方法体执行之前！）
   │     └─ SQL-1：selectOneById（查当前登录用户 → 鉴权）
   │
   ├─ ② 进入 listUserVOByPage 方法体 → userService.page(...)
   │     ├─ SQL-2：COUNT（先数总数）
   │     └─ SQL-3：SELECT ... LIMIT（再取当页数据）
   │
   └─ ③ VO 转换（纯内存，无 SQL）→ 返回 JSON
```

### SQL-1：`SELECT * FROM user WHERE id = ? AND isDelete = 0`（鉴权）

- 触发点：Controller 方法上的 `@AuthCheck(mustRole = ADMIN_ROLE)`；
- AOP 切面 `AuthInterceptor`（`@Around("@annotation(authCheck)")`）在方法执行前调用 `userService.getLoginUser(request)`；
- `getLoginUser` 先从 session 取用户（无 SQL），再 `this.getById(...)` 查库——**因为 session 里的用户是登录那一刻的快照，可能已被降权/封号，要查库拿最新角色**来比对注解要求的权限。

### SQL-2：`SELECT COUNT(*) FROM user WHERE isDelete = 0`（数总数）

- 触发点：`userService.page(Page.of(pageNum, pageSize), queryWrapper)`；
- MyBatis-Flex 的 `page()` 内部固定两步，**第一步 COUNT**——用于计算响应中的 `totalRow` / `totalPage`；
- `isDelete = 0` 是实体逻辑删除字段自动追加的，不是手写条件。

### SQL-3：`SELECT ... FROM user WHERE isDelete = 0 LIMIT 0, 10`（取当页）

- `page()` 的第二步，按 `offset = (pageNum-1) × pageSize` 换算 LIMIT，捞当页数据。

### 后续（无 SQL）

- `new Page<>(pageNum, pageSize, totalRow)` 组装 VO 分页壳；
- `getUserVOList(...)` 做 User → UserVO 脱敏（内存 BeanUtil 拷贝），**userPassword 在这一步被丢弃，不进响应**。

### 细节优化点

SQL-3 的列清单里包含 `userPassword`——密码哈希确实查到了内存，只是 VO 转换时丢弃。功能上安全，但更严谨的做法是在 QueryWrapper 里 `.select(...)` 指定列，让敏感字段从源头不落内存。

---

## 五、为什么分页必须先 COUNT？

COUNT 不是为了取数据，而是为了**响应里的 `totalRow` / `totalPage` 两个字段**：

- `LIMIT 0, 10` 只回答"这一页有什么"，回答不了"一共多少条"（库里 2 条、37 条、200 万条，LIMIT 0,10 的返回看起来都一样）；
- 前端分页条渲染需要："共 N 条记录，第 x/y 页"、页码画几个、下一页按钮是否可点；
- `totalPage = ceil(totalRow / pageSize)`——公式前提是先有 totalRow，所以 `page()` 必须 COUNT + LIMIT 两条 SQL。

### 替代方案：游标分页（不需要 COUNT）

- 只查 `LIMIT pageSize + 1`（多捞一条）；
- 返回了 pageSize+1 条 → `hasMore: true`，把最后一条的 id 作为游标给前端；
- 返回 ≤ pageSize 条 → 没有下一页；
- 适用于无限下拉场景（朋友圈、feed 流），因为大数据量下 `COUNT(*)` 在 InnoDB 很慢。

**结论**：管理后台（数据量可控、需要完整页码条）用 offset 分页 + COUNT 是标准选择；海量列表再换游标分页。

---

## 六、为什么分页必须要有 pageSize 和 pageNum？

最终 SQL 是 `LIMIT 偏移量, 每页条数`，两个参数正好一个算"取几条"、一个算"从哪开始"：

```
LIMIT 0, 10    ← 第 1 页：(1-1)×10 = 0
LIMIT 10, 10   ← 第 2 页：(2-1)×10 = 10
LIMIT 20, 10   ← 第 3 页：(3-1)×10 = 20

换算公式：offset = (pageNum - 1) × pageSize
```

### 各自的职责

- **pageSize（取几行）**：SQL 必须有行数上限，否则等于让数据库把整张表吐回来，大表会打挂服务。`PageRequest` 里默认 `pageSize = 10` 就是兜底。
- **pageNum（从哪开始）**：只有 pageSize 永远只能拿第一页；pageNum 提供翻页能力。

### 为什么不让前端直接传 offset？

1. **语义友好**："第 3 页" 比 "偏移量 20" 直观，分页组件交互的就是页码；
2. **可约束**：pageNum 最小为 1 天然有下界；offset 可传负数、天文数字，后端要额外防御；
3. **换算责任在后端**：pageSize 变化（如手机端每页 5 条）时前端页码逻辑不用改。

### 生产注意：pageSize 必须设上限

前端传 `pageSize=999999` 会绕过分页保护，后端要拦截：

```java
ThrowUtils.throwIf(userQueryRequest.getPageSize() > 100, ErrorCode.PARAMS_ERROR, "pageSize 过大");
```

---

## 本轮要点速查

| 主题 | 一句话结论 |
|---|---|
| 请求日志 | Spring Boot 默认不打请求日志；web/mapper 包开 debug |
| `parameters={}` | 只显示 URL 查询参数，JSON body 在 `Read ... to [...]` 行 |
| 动态查询 | 空字符串不会像 null 一样被自动忽略，必须 `isNotBlank` 判断 |
| 分页三条 SQL | 鉴权查库 1 条 + COUNT 1 条 + LIMIT 1 条 |
| COUNT 的意义 | 算 totalRow/totalPage 给前端分页条，不是取数据本身 |
| offset 公式 | `offset = (pageNum - 1) × pageSize` |

---

# 2026/08/26

## 一、为什么 AiCodeGeneratorService 必须用工厂类（@Configuration + @Bean）注册，不能在接口上加 @Service？

**背景**：langchain4j 的 `AiCodeGeneratorService` 是一个只有方法声明的接口，测试里却能直接 `@Resource` 注入——它能被注入，恰恰是因为 `AiCodeGeneratorServiceFactory` 里那个 `@Bean` 方法在背后把它造出来。删掉工厂类，注入立刻报 `NoSuchBeanDefinitionException`（看不见 ≠ 不存在）。

### 为什么 @Service 不行

`@Service` / `@Component` 对 Spring 说的话是：**"这个类你自己 new 一个出来注册成 Bean"**（Spring 通过反射调用构造函数实例化）。

而接口没有构造函数、没有实现体，`new AiCodeGeneratorService()` 在 Java 里是非法的。真在接口上贴 @Service，启动时直接报：

```
BeanCreationException: Specified class is an interface
```

那个"能被注入的接口对象"其实是 `AiServices.create(...)` 用 **JDK 动态代理**在运行时生成的实现类（拦截方法调用 → 拼 prompt → 调模型 → 返回结果）。**代理对象的出生必须靠调用这个方法**，而 Spring 不会"自己想到"去调第三方库的工厂方法——所以必须由你通过 @Bean 提供创建逻辑。

### Spring 注册 Bean 的三条路

| 方式 | Spring 做什么 | 适用对象 |
|---|---|---|
| `@Service`/`@Component` + 包扫描 | **Spring 负责 new**（反射调构造函数） | 自己写的**具体类**，如 `UserServiceImpl` |
| `@Bean` 方法（工厂类） | **你负责 new**（写创建逻辑），Spring 只调用你的方法并管理生命周期 | 第三方库的对象、需要特殊构造逻辑的对象（如动态代理） |
| `ImportBeanDefinitionRegistrar`（@MapperScan、@AiService） | 框架批量扫描接口，**框架生成代理**并塞进容器 | 接口 + 动态代理的场景 |

一句话记：**@Service 是"Spring 你来造"，@Bean 是"我造好了给你"**。动态代理生成的对象只能走后两条路。

### 项目内的现成对照

- `UserServiceImpl` 上是 `@Service` —— 具体类，Spring 能 new ✅
- `UserMapper` 接口什么注解都没有但能注入 —— MyBatis-Flex 的启动器走了第三条路，扫描接口批量生成代理 Bean
- `AiCodeGeneratorService` 接口靠 `@Bean` 工厂方法 —— 第二条路

### 想要"接口上贴注解"的等价写法

langchain4j 提供了 `@AiService`（需额外引入 `langchain4j-spring-boot-starter`），贴在接口上即可自动注册：

```java
@AiService   // 相当于 AI 界的 @Service，本质走第三条路
public interface AiCodeGeneratorService {
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    String generateHtmlCode(String userMessage);
}
```

它背后就是 langchain4j 替你写好了 Registrar，自动对标注接口做 `AiServices.create` 并注册——**和手写的工厂类干的事一模一样**，只是包成了注解。

### 结论

- `@Configuration + @Bean` 是注册第三方/代理对象的标准姿势，不冗余；
- `@Service` 只能贴在具体类上；
- "工厂"现在的形态只是最简版，价值在后续演进（多智能体接口汇聚创建、挂 ChatMemory、@Tool、条件切换模型），架构类代码看当下像多余，价值在演进时兑现。

---

## 二、@Data 注解是干什么的？（Lombok）

`@Data` 是 **Lombok** 的注解，贴在类上，**编译期**自动生成样板方法（不是运行时魔法）。以项目里的 `UserQueryRequest` 为例：

```java
@Data
public class UserQueryRequest extends PageRequest {
    private Long id;
    private String userName;
    ...
}
```

它等价于手写这 5 样东西：

| 自动生成 | 干什么用 | 没有它会怎样 |
|---|---|---|
| **getter/setter**（`getId()`、`setUserName()`...） | 取值/赋值 | 调用全报红；JSON 反序列化也依赖 setter |
| `toString()` | 打印对象内容 | 日志里只显示 `UserQueryRequest@1a2b3c` 地址，排查困难 |
| `equals()` / `hashCode()` | 对象比较、放进 Set/Map | 内容相同的两个对象判不相等 |
| `@RequiredArgsConstructor` | 生成 final 字段的构造函数 | 无 final 字段时无感 |

**验证方式**：展开 `target/classes` 下的 `.class` 文件（或类内 `Ctrl+F12`），能看到这些方法真实存在。

### 为什么 UserQueryRequest 上还搭配了 @EqualsAndHashCode(callSuper = true)

```java
@EqualsAndHashCode(callSuper = true)
@Data
public class UserQueryRequest extends PageRequest {
```

`@Data` 生成的 `equals/hashCode` **默认不包含父类字段**。`UserQueryRequest` 继承了 `PageRequest`（含 pageNum/pageSize），不加 `callSuper = true`，两个 userName 相同但页码不同的对象会被判相等——比较时丢掉父类信息。**有继承关系的类，@Data 旁边记得带它**。

### 常见伙计 @Slf4j

同类思路，自动生成 `private static final Logger log` 字段，所以 `GlobalExceptionHandler` 里能直接 `log.error(...)`。

### 使用提醒

`@Data` 适合 **DTO / VO / 实体类**（当前项目用法即标准姿势）；不要在需要不可变性的类上滥用——只读对象用 `@Value`（全字段 final）或手动控制。

---

## 三、System.getProperty("user.dir") 是什么语法？

JDK 自带 API：`System.getProperty(键)` —— 读取 **Java 系统属性**（JVM 启动时维护的一组全局键值对配置）。

```java
System.getProperty("user.dir")
│            │          │
│            │          └── 属性名（key）：当前工作目录
│            └── 方法：按 key 取属性值，返回 String
└── java.lang.System 类（核心类，无需 import）
```

`"user.dir"` 固定表示 **JVM 进程的当前工作目录**——即"从哪个目录启动的 Java 程序"。

### 在 CodeFileSaver 场景中的含义

```java
private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";
```

- **IDEA 里运行**：`user.dir` = 项目根目录（`E:\development\codeFather\cc-ai-code-mother`），代码存到 `项目根/tmp/code_output`；
- **命令行 `java -jar` 部署**：`user.dir` = 敲命令时所在目录。

**坑**：工作目录"取决于从哪启动"，不是固定值——同一个 jar 在不同目录启动，文件就存到不同地方。生产上应改为可配置项（放 yml）。

### 常用系统属性速查（同一 API）

| 属性 key | 含义 | 典型值 |
|---|---|---|
| `user.dir` | 当前工作目录 | `E:\development\codeFather\cc-ai-code-mother` |
| `user.home` | 用户主目录 | `C:\Users\86187` |
| `os.name` / `os.version` | 操作系统 | `Windows 11` |
| `file.separator` | 路径分隔符 | Windows `\`、Linux `/` |
| `line.separator` | 换行符 | Windows `\r\n` |
| `java.version` | JDK 版本 | `21.0.12` |

延伸：`System.setProperty("key", "value")` 可写属性；启动参数 `-Dkey=value` 可注入属性（如 `-Dfile.encoding=UTF-8`），同一套体系。

---

## 四、为什么编译器提示 'default' branch is unnecessary？

**场景**：`AiCodeGeneratorFacade.generateAndSaveCode` 里对枚举 `CodeGenTypeEnum` 用 switch 表达式，两个枚举值都被 case 覆盖，IDEA 却给 `default` 分支标黄。

```java
return switch (codeGenTypeEnum) {
    case HTML -> generateAndSaveHtmlCode(userMessage);
    case MULTI_FILE -> generateAndSaveMultiFileCode(userMessage);
    default -> {  // ← 'default' branch is unnecessary
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型");
    }
};
```

### 原因：default 是永远到不了的死代码

枚举总共只有 `HTML`、`MULTI_FILE` 两个值，case 已全部接住。能传入的情况只有三种：`HTML`（第一个 case）、`MULTI_FILE`（第二个 case）、`null`——而 `null` 要么被方法开头的 if 提前拦截，要么枚举 switch 遇 null 直接抛 NPE，都进不了 default。**零入口 = 死代码**，IDEA 的 dead code 检查标黄。

### 背后知识点：switch 表达式的穷尽性检查（Java 14+）

switch **表达式**（`->` 带返回值那种）有编译器强制规则：**必须覆盖所有可能情况，否则编译不过**。对枚举，覆盖了全部枚举值即满足穷尽性，不需要 default。

**删掉 default 反而更安全**——枚举扩展时的行为对比：

| 以后新增 `REACT` 枚举值 | 保留 default | 删掉 default |
|---|---|---|
| 何时暴露遗漏 | **运行时**才抛"不支持的类型"（没测到就漏上线） | **编译直接报错**，所有漏改的 switch 全部标红，强制补处理 |

编译器成了免费的穷尽性守卫。

### 对比：老式 switch 语句没有这层保护

```java
// 老式 switch 语句（case XXX: ... break; 不带返回值）
// 没有 default 也不报错、新枚举值漏了也不报错
// → 那种写法里 default 兜底抛异常才是必要防御
```

**结论**：新式 switch 表达式 + 枚举全覆盖时，放心删 default，把防御交给编译器。

---

## 五、@SpringBootTest 注解的作用，与"分层测试"的关系

**作用一句话**：在跑测试前**把整个 Spring 容器完整启动一遍**，让测试类像正式应用一样使用依赖注入、配置、Bean。

### 没有 vs 有 的对比（以 AiCodeGeneratorServiceTest 为例）

```java
@SpringBootTest                        // ← 关键
class AiCodeGeneratorServiceTest {
    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;  // 接口也能注入

    @Test
    void generateHtmlCode() {
        aiCodeGeneratorService.generateHtmlCode("...");     // 直接调用
    }
}
```

| | 不加 @SpringBootTest | 加 @SpringBootTest |
|---|---|---|
| 测试怎么跑 | 纯 JUnit，new 个对象就跑 | 先启动 Spring 容器，再跑测试方法 |
| `@Resource` 注入 | **null**，调用直接 NPE | 正常注入工厂类创建的代理 Bean |
| application-local.yml 生效？ | ❌ | ✅（所以能读到 DeepSeek 的 key） |
| `@Value`、数据源、AOP 切面 | 全部不工作 | 全部生效 |

测试能真发请求到 DeepSeek 的完整链条：`@SpringBootTest` 启动容器 → langchain4j starter 读 yml 建 `ChatModel` → 工厂类 `@Bean` 建出 `AiCodeGeneratorService` → 注入测试类。抽掉注解，整条链断掉。

### 它背后做的事

1. 找到 `@SpringBootApplication` 标注的主类；
2. 完整执行 Spring 启动流程：加载 yml → 扫描 Bean → 依赖注入 → AOP 代理 → 数据源初始化；
3. 容器就绪后执行 `@Test` 方法，测试结束容器销毁。

### 细节：默认不启动 Web 服务器

默认 `webEnvironment = MOCK`——只建容器和 Mock 的 Servlet 环境，**不真起 Tomcat**（8123 不会被占用）。需要真发 HTTP 请求时才改：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
```

### 它与分层测试的关系（不是二选一的兜底，是并列的两套思路）

- `@SpringBootTest` = **不分层**，全量加载，端到端集成测试，测"整条链能不能跑通"；代价是慢、依赖真实环境；
- **测试切片**注解 = 分层测试，只加载要测的那一层，其他层用 `@MockBean` 造假。

| 注解 | 只加载哪层 | 适合测 |
|---|---|---|
| `@SpringBootTest` | 全部 | 端到端集成测试 |
| `@WebMvcTest(UserController.class)` | 指定 Controller + MVC 层 | 参数校验、状态码、JSON 格式 |
| `@DataJpaTest` | JPA + 数据源（MyBatis-Flex 项目不直接适用） | ORM 映射、SQL |
| `@JsonTest` | JSON 序列化器 | VO/DTO 序列化格式 |
| 纯 JUnit（什么都不加） | 无容器 | 工具类、算法、纯逻辑 |

### 分层测试示例（@WebMvcTest + @MockBean，不连数据库）

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;          // 模拟发 HTTP 请求，不起真 Tomcat

    @MockBean
    UserService userService;  // Service 换成 Mockito 造假对象

    @Test
    void getUserById_returnsUser() throws Exception {
        when(userService.getById(1L)).thenReturn(fakeUser);  // 指定假返回值

        mockMvc.perform(get("/user/get").param("id", "1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code").value(0));
    }
}
```

### 怎么选（实用原则）

| 想验证什么 | 用什么 |
|---|---|
| 整条链路真的能通（配置对、Bean 装配对、真能调通 AI/DB） | `@SpringBootTest` |
| 某一层的边界行为（参数校验、异常处理、状态码） | `@WebMvcTest` 等切片注解 |
| 纯逻辑（工具类、算法、分支判断） | 纯 JUnit |

跟教程学习阶段用 `@SpringBootTest` 够用且直观；自己项目追求测试速度时，按"能切片就切片，切片不了才上全量"收紧。

# 2026/08/27

## 一、CodeParser 正则解析：extractHtmlCode 方法与 group(1) 详解

**场景**：AI 返回的是带 Markdown 围栏的文本，`CodeParser` 用正则从中抠出纯代码。

```java
private static final Pattern HTML_CODE_PATTERN =
        Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

private static String extractHtmlCode(String content) {
    Matcher matcher = HTML_CODE_PATTERN.matcher(content);  // ① 正则绑定目标文本，得到匹配器
    if (matcher.find()) {                                  // ② 找下一个匹配，找到返回 true
        return matcher.group(1);                           // ③ 取第 1 个捕获组（纯代码）
    }
    return null;                                           // ④ 没有代码块 → null
}
```

### 正则逐段拆解（对着 AI 返回的 ```html 代码块看）

| 正则片段 | 匹配什么 |
|---|---|
| ` ```html ` | 字面量：三个反引号 + html |
| ` \s* ` | 任意空白 0+ 个（容忍行尾空格） |
| ` \n ` | 换行符 |
| ` ( ... ) ` | **捕获组**：把这段内容"装进抽屉"，供 group(1) 取 |
| ` [\s\S] ` | 空白或非空白 = 任意字符**含换行**（`.` 默认不匹配换行，多行代码必须用它） |
| ` *? ` | **懒惰量词**：尽量少匹配，到第一个 ` ``` ` 就停（贪婪 `*` 会吞到下一个代码块） |
| `CASE_INSENSITIVE` | 大小写不敏感，` ```HTML ` 也能匹配 |

### group(0) 与 group(1) 的区别

group 不是"智能识别哪段是代码"，而是**括号位置决定的手动贴标签**：

```
命中整体：  ```html\n<!DOCTYPE html>...</html>\n```
            └────────── group(0)：整个匹配（含围栏）──────────┘
            ```html\n  <!DOCTYPE html>...</html>  \n```
            ──围栏──     └──── group(1)：括号围住的代码 ────┘   ──围栏──
```

- `group(0)`：整个匹配，**规范固定白送，不算捕获组**；
- `group(1)`：正则里**从左数第 1 个 `(`** 包住的内容——是代码，纯粹因为括号恰好套在那里。

### 捕获组编号规则与数量

- **捕获组数量 = 未转义的 `(` 的个数**（`matcher.groupCount()` 可查，不含 group 0）；
- 编号按左括号出现顺序：第 1 个 `(` → group(1)，第 2 个 → group(2)…

```java
// 多组验证编号
Pattern.compile("(\\d{4})-(\\d{2})").matcher("日期是 2026-08-26")
group(0) → "2026-08-26"（整个匹配）
group(1) → "2026"（第1个括号）
group(2) → "08"（第2个括号）
```

- **`(?:` 非捕获组**：只要分组功能（如 `js|javascript` 多选一），不占编号、不建抽屉。项目里 JS 正则 ```` ```(?:js|javascript)\s*\n([\s\S]*?)``` ```` 虽有两对括号，但 `(?:...)` 不算，所以依然只有 1 个捕获组。

### Java 正则三件套

| 角色 | 职责 |
|---|---|
| `Pattern` | 正则编译产物，可复用（所以提成 `static final`，只编译一次） |
| `Matcher` | 绑定目标文本的匹配状态机，负责 find / group |
| `find()` vs `matches()` | find 找**子串**（可滚动多次）；matches 要求匹配**整个**字符串 |

### 防御性设计

`parseHtmlCode` 在 `group(1)` 为 null 时**把整段原文当 HTML 兜底**——prompt 强制了格式，但代码层不信任 LLM 输出，永远留 fallback。这是处理 LLM 输出的通用姿势。

---

## 二、为什么 parseMultiFileCode 不用 fallback？解析不到时用户最终拿到什么？

### 单文件 fallback 为什么成立

`parseHtmlCode` 解析不到代码块时把**整段原文当 HTML**：

```java
} else {
    result.setHtmlCode(codeContent.trim());  // 整段原文当 HTML
}
```

成立的前提：AI 的任务就是**只输出 HTML**，即使没写 ```html 围栏，整段文本大概率还是 HTML——存成 index.html 浏览器八成能打开。**fallback 的结果虽不完美，但大概率可用**。

### 多文件 fallback 为什么不成立

若多文件模式也做"整段当 HTML"，拿到的是 HTML/CSS/JS **混在一起的裸文本**：

```
<div class="container">...</div>
body { margin: 0 }
function init() { ... }
```

- "整段当 HTML"意味着 CSS 和 JS 会作为**正文文本**出现在网页里——保存出来的文件是错的；
- 三种代码**没有任何可靠依据拆分**——哪段进 style.css？哪段进 script.js？

**设计原则：错误的文件比没有文件更糟**：

| | 单文件 fallback | 多文件硬 fallback（假设做） |
|---|---|---|
| 结果 | 大概率可用的页面 | 确定错乱的文件（CSS/JS 变成网页正文） |
| 用户感知 | "页面有点糙但能用" | "这是什么乱码？"还以为系统坏了 |
| 掩盖问题程度 | 可接受 | 严重——把 AI 的格式违规静默转嫁成坏产物 |

所以多文件选择**解析不到就不填**（字段留 null）——fail fast 思路：宁可让失败**显式暴露**给上层，也不猜一个错误结果。**fallback 的合法性取决于"猜错时代价多大"：单文件猜错代价小，值得兜底；多文件猜错代价大，宁可显式失败。**

### 前置事实：CodeParser 已不在主链路

全局搜索确认 `CodeParser` 无任何调用——接口已改用 langchain4j **结构化输出**，`generateMultiFileCode` 直接返回 `MultiFileCodeResult`，AI 输出 JSON 由框架反序列化，正则解析被替代。但问题依然成立：**结构化输出的字段也可能是 null/空**（AI 没按 JSON schema 填全）。

### 完整追踪：字段为空时用户最终拿到什么（无 NPE 情况）

```
AI 输出的 JSON 缺字段
   ↓
generateMultiFileCode 返回 MultiFileCodeResult（htmlCode = null）
   ↓
CodeFileSaver.saveMultiFileCodeResult(result)
   ↓
writeToFile(dir, "index.html", null)          ← content 是 null
   ↓
FileUtil.writeString(null, filePath, UTF_8)   ← hutool 内部用 PrintWriter.print(content)
   ↓
PrintWriter.print((String) null)              ← Java 规范：打印字面量 "null"！
   ↓
磁盘上生成内容为 "null" 四个字符的文件
   ↓
Facade 正常返回 File 目录 → HTTP 200 / code=0 "成功"
```

**用户最终拿到：一个"成功"的响应 + 一个打开后页面上写着 `null` 两个字的网站。** 不报错、不降级、静默产出废品。

三个文件的"惨状"各有不同：

| 文件 | 内容 | 浏览器里的表现 |
|---|---|---|
| index.html | `null` | 白页上渲染出 "null" 文本 |
| style.css | `null` | 非法 CSS，被浏览器静默忽略（无样式） |
| script.js | `null` | 恰好是合法 JS 表达式语句，无任何效果 |

### 关键知识点：PrintWriter.print(null) 不抛异常

`PrintWriter.print((String) null)` 按规范**打印字面量字符串 "null"**，而不是 NPE。所以"没有 NPE 的情况下"的答案就是静默的 `"null"` 文件——某种意义上**比 NPE 更糟**：NPE 至少让用户知道出错了，这个看起来一切正常。

### 三种结局对比

| 情形 | 用户拿到 | 性质 |
|---|---|---|
| 单文件模式解析不到 | 整段原文当 HTML（降级但大概率可用） | ✅ 合理降级 |
| 多文件模式字段为空 | 200 成功 + 内容为 "null" 的废文件 | ❌ **静默失败** |
| langchain4j JSON 解析直接失败 | 抛异常 → 全局处理器 → "系统错误" | ✅ 显式失败（可接受） |

### 已实施的修复：门面层校验（本项目最终方案）

```java
private File generateAndSaveMultiFileCode(String userMessage) {
    MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
    // AI 输出不可信：字段缺失时不落盘，显式报错引导重试
    ThrowUtils.throwIf(StrUtil.hasBlank(result.getHtmlCode(), result.getCssCode(), result.getJsCode()),
            ErrorCode.OPERATION_ERROR, "AI 输出不完整，请重试");
    return CodeFileSaver.saveMultiFileCodeResult(result);
}
```

单文件的降级保留（语义成立），多文件补上校验——三种模式行为都"诚实"了：**要么可信的结果，要么明确的错误，绝不给看似成功的废品**。每一层要么给出可信的结果，要么把失败说清楚。

---

## 三、单元测试在实际企业规范中应该提交到 Git 么？

**结论：应该提交，而且是必须提交**——单元测试在企业规范里是一等公民代码，和业务代码同等地位。

### 为什么必须提交

| 角度 | 没有测试进仓库会怎样 |
|---|---|
| **回归保护** | 测试不提交 = 只在自己电脑上跑过。别人改了代码触发回归，CI 上没有任何测试拦截，坏代码直接上线 |
| **协作** | 同事接手模块时，测试就是**活文档**：怎么调用、边界在哪、预期行为是什么——比文档可信（文档会过期，测试跑不过就报警） |
| **CI/CD 前提** | 企业流水线 `mvn test` 阶段跑的就是仓库里的测试。测试不在仓库 = 流水线形同虚设 |
| **重构勇气** | 有测试兜底才敢大改；没有测试谁都不敢动，最后变成"祖传屎山" |
| **代码评审** | PR 里的测试是评审的重要部分——"这个改动覆盖了哪些情况"一目了然 |

### 企业实际规范（普遍共识）

- ✅ `src/test/java` **整体提交**，和 `src/main/java` 同等对待；
- ✅ 测试代码同样走 Code Review，同样有质量要求（命名、断言、不留死测试）；
- ✅ 甚至有"测试覆盖率门禁"：新增代码覆盖率低于阈值（如 60%/80%）流水线直接失败；
- 常见 commit 惯例：测试跟着功能走同一个 commit（`feat: 新增XX` 里含测试），或紧跟一个 `test: 补充XX测试`。

### 什么测试不提交（例外清单）

| 类型 | 例子 | 处理 |
|---|---|---|
| 临时调试代码 | main 方法里随便试试、打印看看 | 删掉或注释，不提交 |
| 依赖个人环境的联调脚本 | 硬编码本机路径 `E:\xxx`、真实 API key | 不提交（或改造后提交） |
| 破坏性的集成测试 | 每次跑都真实调 DeepSeek 花钱、删生产数据 | 用 `@Disabled` / `@Tag` 标记隔离，或放单独 profile |
| 性能压测草稿 | 本机随手 benchmark | 不提交 |

### 对照本项目的处理

`src/test/java/com/zjcc/ccaicodemother/` 里的测试分两类，命运不同：

1. **`AiCodeGeneratorFacadeTest` / `CodeParserTest`**（纯逻辑，不花钱不依赖环境）→ **提交** ✅
2. **`AiCodeGeneratorServiceTest`**（`@SpringBootTest` 真调 DeepSeek，消耗 API 额度）→ 属于**集成测试**，企业常见做法：
   - 提交但加 `@Disabled("手动触发，消耗AI额度")` 或 JUnit 的 `@Tag("integration")`，CI 默认跳过；
   - 或改成 `@EnabledIfEnvironmentVariable`——设了特定环境变量才跑。

   裸提交的问题：同事拉下代码跑 `mvn test`，莫名其妙等半天还烧了 API 额度。

### 一句话总结

> 测试代码是资产不是草稿，提交是常态；唯一要动脑子的是"会不会在别人机器上产生副作用（花钱、依赖环境）"——这类**标记隔离后照常提交**。

---

## 四、doOnComplete 要求 Runnable？`() -> {}` 

**场景**：`AiCodeGeneratorFacade.generateAndSaveHtmlCodeStream` 流式生成代码时：

```java
StringBuilder codeBuilder = new StringBuilder();
return result
        .doOnNext(chunk -> codeBuilder.append(chunk))   // 每个片段实时收集
        .doOnComplete(() -> {                            // 流结束后保存
            String completeHtmlCode = codeBuilder.toString();
            HtmlCodeResult htmlCodeResult = CodeParser.parseHtmlCode(completeHtmlCode);
            CodeFileSaver.saveHtmlCodeResult(htmlCodeResult);
        });
```

Reactor 的签名：`Flux<T> doOnComplete(Runnable onComplete)`——参数是 Runnable。

### 为什么 `() -> { ... }` 能塞进去：函数式接口 + 目标类型推断

`Runnable` 是**函数式接口**（只有一个抽象方法）：

```java
@FunctionalInterface
public interface Runnable {
    void run();      // ← 无参数、无返回值
}
```

lambda `() -> { ... }` 的形状恰好是**无参、无返回**，与 `run()` 签名完全吻合。**Java 8 规则：lambda 可以实现任何"形状匹配"的函数式接口**——编译器看到参数类型是 Runnable，检查 lambda 能否作为 run() 的实现体，能就通过（目标类型推断）。同一个 lambda 文本，塞进不同接口就是不同的东西：

```java
() -> 42      // 塞给 Supplier<Integer> 合法（无参有返回）
() -> save()  // 塞给 Runnable 合法（无参无返回）
x -> x * 2    // 塞给 Function<Integer,Integer>（一参一返回）
```

Java 8 之前的等价老写法（lambda 是它的语法糖）：

```java
.doOnComplete(new Runnable() {
    @Override
    public void run() { /* 保存代码 */ }
});
```

> 严格说 lambda 底层不是匿名内部类（编译成 `invokedynamic`，由 LambdaMetafactory 运行时生成实现，不产生独立 .class 文件，`this` 指向外围类）——但概念上理解为"函数式接口的匿名实现"完全够用。

### 澄清误解：这没有开新线程

Runnable ≠ 线程，两件事拆开：

| | 是什么 |
|---|---|
| `Runnable` | 一段"无参无返回的代码清单"，**本身不碰线程** |
| `Thread` / 线程池 | 执行代码的东西。Runnable 只有被**显式交给** `new Thread(r).start()` 或 `executor.submit(r)` 才和线程挂钩 |

`doOnComplete(Runnable)` 里 Runnable 的角色是**回调**：Reactor 在"流结束"信号到来时，**在当前处理信号的线程上直接调用 `run()`**——不开线程。对本项目：保存代码跑在 **langchain4j 流式 HTTP 客户端推送完成信号的线程**上（Reactor Netty 的 IO 事件线程），不是主线程、也不是新开的线程。

对比真开线程的写法：

```java
new Thread(() -> CodeFileSaver.saveHtmlCodeResult(result)).start();  // 这才开新线程
.doOnComplete(() -> { ... })                                          // 只是注册回调，信号来了就地执行
```

想让保存逻辑切到别的线程，要显式 `publishOn(Schedulers.xxx)` 或自己提交线程池。

### 顺带：lambda 捕获变量的规则

`codeBuilder` 同时被 `doOnNext` 和 `doOnComplete` 两个 lambda 引用——lambda 能捕获外围局部变量，但要求 **effectively final**（不能重新赋值）。所以用 `StringBuilder`（引用不变、内容可变）而不是 `String` 拼接。

### 总结

> `() -> {}` 是 Runnable 的 lambda 写法（形状匹配 + 目标类型推断）；Runnable 在这只是"无参无返回的回调契约"，doOnComplete 在**信号线程**上同步执行它，不涉及开线程。

# 2026/08/29

## 一、新方案测试：完整测试代码

```java
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
        File file = aiCodeGeneratorFacade.generateAndSaveCode("简易个人博客", CodeGenTypeEnum.HTML);
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
        File file = aiCodeGeneratorFacade.generateAndSaveCode("任务记录网站", CodeGenTypeEnum.MULTI_FILE);
        Assertions.assertNotNull(file);
        Assertions.assertTrue(file.exists());
        // 目录名以 multi_file_ 开头
        Assertions.assertTrue(file.getName().startsWith("multi_file_"));
        // 新方案校验规则：htmlCode 必填，css/js 可为空（为空则不写文件）
        Assertions.assertTrue(new File(file, "index.html").exists());
    }

    @Test
    void generateAndSaveCodeStreamWithHtml() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream("任务记录网站", CodeGenTypeEnum.HTML);
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
                () -> aiCodeGeneratorFacade.generateAndSaveCode("测试", null));
        assertNotNull(exception.getMessage());
    }

    @Test
    void generateAndSaveCodeStreamRejectsNullType() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiCodeGeneratorFacade.generateAndSaveCodeStream("测试", null));
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
}
```

## 二、新方案测试为什么这么改？逐项意图

**主线**：旧测试"跑通了"但几乎测不出任何 bug；新测试让每条断言都真正具备"失败能力"。**旧测试验证"代码跑过了"，新测试验证"承诺兑现了"。**

### 1. 断言从 assertNotNull 升级为行为验证

```java
// 旧：只证明"返回了个对象"，返回错误路径/没建目录照样绿
Assertions.assertNotNull(file);

// 新：功能承诺了什么就验证什么
Assertions.assertTrue(file.exists());                          // 承诺1：目录真的创建了
Assertions.assertTrue(file.isDirectory());                     // 承诺2：是目录
Assertions.assertTrue(file.getName().startsWith("html_"));     // 承诺3：走对了模板分支 ★
Assertions.assertTrue(new File(file, "index.html").exists());  // 承诺4：文件真的落盘
```

`html_` 前缀来自 `CodeFileSaverTemplate.buildUniqueDir()` 里的 `getCodeType()`——**断言前缀 = 验证策略分发到了正确的子类**，这是新架构最容易出错的地方（switch 写错分支、子类 getCodeType 返回错枚举）。

### 2. 修掉一个"永远不可能失败"的断言

```java
String completeContent = String.join("", result);
Assertions.assertNotNull(completeContent);   // ← 永远通过！
```

`String.join` 的返回值**永远不为 null**（列表空时返回 `""`，JDK 契约）——不可能失败的断言等于没断言。换成有区分度的：

```java
assertFalse(completeContent.isBlank(), "流式内容不应为空");
```

**测试哲学：测试的价值 = 它能失败的方式。写完每条断言问自己"什么样的 bug 会让它变红？"答不上来的断言是装饰品。**

### 3. 补上负向用例（零成本守卫）

旧测试只测正常输入，Facade 的 null 拦截防御没有测试盯着——下次重构被"顺手删了"都没人知道。新增 `generateAndSaveCodeRejectsNullType` / `generateAndSaveCodeStreamRejectsNullType`：把防御代码钉死在测试保护下，且不调 AI、不花钱、秒级跑完，可随时快速回归。

### 4. 流式用例新增落盘验证

流式方法的核心承诺是"流结束后自动解析并保存"（doOnComplete），旧测试只验证"收到了字符串"。新增查找保存目录的断言，守住 processCodeStream 完整链路。时序保证：`doOnComplete` 回调先于 `block()` 返回（collectList 在它下游，完成信号要穿过 doOnComplete 才到 block），所以 block() 返回后查文件系统是安全的。

### 5. 覆盖从 2/4 补齐

旧测试是"对角覆盖"（HTML同步 + MULTI_FILE流式），多文件保存目录结构（写3个文件）和 HTML 流式保存从未验证。流式只保留 HTML 一个是**成本权衡**：流式链路已被验证，多文件流式边际覆盖小，但每次真金白银调一次 DeepSeek。

### 6. 命名从"方法名复读"改为"场景描述"

```
旧：generateAndSaveCode()          ← 和被测方法同名，看不出场景
新：generateAndSaveCodeWithHtml()  ← 一眼看出：HTML 类型、同步入口
```

测试名是第一文档——将来挂了不看代码就知道哪个场景坏了。

### 意图速查

| 改动 | 旧测试的洞 | 新测试的意图 |
|---|---|---|
| 断言升级 | assertNotNull 测不出实现 bug | 每条断言对应可失败的承诺 |
| isBlank 替代 assertNotNull | String.join 永不返回 null | 让断言具备失败能力 |
| 负向用例 | 防御代码无保护 | 钉死 null 拦截，零成本回归 |
| 落盘验证 | 只验证"收到流" | 守住 processCodeStream 完整链路 |
| 覆盖补齐 | 2/4 对角组合 | 2×2 主路径 + 负向，流式按成本取舍 |
| 命名 | 与方法同名无信息量 | 名字即场景，失败秒定位 |

---

## 三、findLatestSavedDir 的必要性：为什么要搜目录？

### 根本原因：流式方法的保存是"副作用"，没有返回值可供断言

```java
// 同步方法：保存结果是返回值 → 直接断言
File file = facade.generateAndSaveCode("简易个人博客", HTML);
assertTrue(file.getName().startsWith("html_"));   // ← 有 File 可查

// 流式方法：返回的是"流本身"，保藏在 doOnComplete 回调里悄悄发生
Flux<String> codeStream = facade.generateAndSaveCodeStream("任务记录网站", HTML);
// 返回值里没有任何"保存到哪了"的信息！
```

流式的保存发生在回调内部，**方法返回值对此只字未提**。测试想验证"流结束后真的保存了"，唯一证据来源是**文件系统**——`findLatestSavedDir` 就是"替我去 tmp 目录看一眼"的自动化替身。

### 为什么按前缀搜而不是拼路径

目录名带**雪花 ID**（`html_1948123456789012480`），每次随机生成，测试无法预知完整路径。只能列出根目录下 `html_` 开头的目录证明至少存在一个——前缀由 `getCodeType()` 决定、可预测，恰好也是要验证的分发逻辑。

### "我一眼就能看到 tmp 目录"——但测试不能靠人的眼睛

| 场景 | 人眼看 | 自动断言 |
|---|---|---|
| 现在调试一次 | ✅ | ✅ |
| 3 个月后改了 processCodeStream 跑 mvn test | ❌ 谁会记得去看 | ✅ 红了立刻报警 |
| 同事拉代码跑测试 | ❌ | ✅ |
| CI 每次提交自动回归 | ❌ 没人看 | ✅ |

测试是**回归保险丝**不是一次性检查；依赖"人记得去 tmp 看一眼"的验证，第二次运行就不存在了。

### 诚实局限：这个断言偏弱

如果 tmp 下留着**上次运行**的 `html_xxx` 目录，这次即使保存失败断言依然通过（找到的是旧目录）——它只是冒烟级验证。更严格的写法是**前后快照对比**：

```java
Set<String> before = listDirNames("html_");   // 记录运行前
codeStream.collectList().block();
Set<String> after = listDirNames("html_");
assertTrue(after.size() > before.size(), "应生成新的保存目录");   // 必须多出新目录才证明是"这次"保存的
```

判断断言质量的维度之一：**能区分"这次的行为"和"历史的残留"吗？**

### 顺带的编译知识点

`File::lastModified` 返回 `long`，不能直接当 `Comparator` 用；要包一层：

```java
.max(Comparator.comparingLong(File::lastModified))   // ✅
.max(File::lastModified)                              // ❌ 编译错误
```

---

## 四、数据库表设计 priority 优先级字段设计意图：精选标记、数字 vs 枚举、代码存文件系统
```sql
-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;
```
>priority 优先级字段：我们约定 99 表示精选应用，这样可以在主页展示高质量的应用，避免用户看到大量测试内容。
> 为什么用数字而不用枚举类型呢？原因是这样更利于扩展，比如约定 999 表示置顶；还可以根据数字灵活调整各个应用的具体展示顺序。我们暂时不考虑将应用代码直接保存到数据库字段中，而是保存在文件系统里。这样可以避免数据库和文件存储不一致的问题，也便于后续扩展到对象存储等方案

**三个独立的设计决策**，逐个翻译成人话，并结合本项目现状对照。

### 决策 1：priority=99 表示"精选"——这是给运营留的字段

**背景**：平台任何人都能生成应用，跑几周之后库里会堆满"测试"、"111"、半成品这类垃圾内容。但主页不能把垃圾展示给新用户看。

**解法**：给每条记录一个 `priority` 字段，管理员人工把**优质应用**标成 99。主页查询就变成：

```sql
-- 主页只取精选
SELECT * FROM code_gen WHERE priority = 99 ORDER BY id DESC;
```

普通用户生成的默认是 0（或别的值），自然被过滤掉。**本质：用数据库字段实现"人工筛选/推荐位"**——就像 App Store 的"编辑推荐"、视频网站的"精选"，都是这么个机制。

### 决策 2：为什么用数字不用枚举——要的是"开放的语义 + 排序能力"

| | 枚举（如 `NORMAL/FEATURED/PINNED`） | 数字（0 / 99 / 999） |
|---|---|---|
| 新增语义（比如"置顶"） | 改代码：加枚举值、可能还要改表 | **零改动**：约定 999 即可，改一行数据 |
| 排序 | 枚举本身没有大小语义，想排序还得再配数字 | **天然可排序**：`ORDER BY priority DESC` 一条搞定 |
| 微调单个应用顺序 | 没有中间值可用 | 随便填 98、97、95，UPDATE 一下就行 |
| 可读性 | ✅ 一眼看懂 | ❌ 99 是什么意思必须查文档（魔法数字） |

作者选数字，核心是看中前两条：

```sql
-- 未来加了"置顶=999"后，主页排序自动变成：置顶 > 精选 > 普通，不用改任何代码
SELECT * FROM code_gen ORDER BY priority DESC;
-- 999（置顶）排在 99（精选）前面，天然有序
```

**枚举适合"封闭的分类"**（性别、订单状态、`CodeGenTypeEnum`——就那几种，不会长）；**数字适合"开放的经营性刻度"**（优先级、权重、积分等级——语义会随运营不断发明新档位）。

**诚实的代价**：可读性差。所以规范的做法是**数据库存数字、Java 代码里定义常量**（就像项目里 `UserConstant.ADMIN_ROLE` 那样）：

```java
public interface CodeGenConstant {
    int PRIORITY_FEATURED = 99;   // 精选
    int PRIORITY_PINNED = 999;    // 置顶（未来）
}
```

查询用常量而不是裸写 99。

### 决策 3：代码存文件系统、数据库只存"路径"——单一事实来源

生成的应用是**一组文件**（index.html / style.css / script.js），有两个地方可以放：

```
方案A：代码内容存数据库（TEXT 字段）
方案B：代码存磁盘目录，数据库只记路径  ← 作者选的，也是本项目现在的做法
```

**"数据库和文件存储不一致"是什么意思？** 关键在于：用户最终要**通过浏览器访问生成的页面**，页面必须是真实的文件（静态资源）。所以选 A 的命运是：

```
代码存 DB → 用户要访问时 → 从 DB 读出来 → 再写回磁盘成文件 → 才能当静态资源伺服
                        ↑
                同一份代码存在两个地方（DB 一份、临时文件一份）
                文件被删了？DB 更新了文件没同步？→ 数据不一致，永远处理不完这种边角
```

选 B 则**世界上只有一份代码**（磁盘上），数据库行只是"指向它的名片"（记路径如 `tmp/code_output/html_1948xxx/`）。要展示就直接伺服那个目录，单一事实来源，不存在同步问题。

另外两个附带好处：

1. **性能与体积**：代码动辄几十 KB～几 MB，塞进数据库会把行撑大、拖慢查询和备份；文件天生就该在文件系统里
2. **"便于扩展到对象存储"**：因为数据库只存**相对路径**这个字符串，以后想从本地磁盘换成阿里云 OSS/S3，只要改"路径 → 文件"的读取实现，**表结构一行不用动**：

```
现在：path = tmp/code_output/html_xxx  →  读本地磁盘
将来：path = code_output/html_xxx      →  拼上 OSS 的 bucket 前缀，逻辑完全一样
```

对照本项目：`CodeFileSaverTemplate` 已经把文件写到 `tmp/code_output/{type}_{雪花ID}/`，下一步教程建 `code_gen` 表时就会有个字段存这个路径——整条设计是一脉相承的。

### 一句话总结三个决策

> 1. **priority 字段** = 人工运营的"精选开关"，过滤垃圾内容；
> 2. **用数字不用枚举** = 语义开放可扩展（99 精选、999 置顶）+ 数字天然能排序，代码里配常量补可读性；
> 3. **代码在文件系统、DB 只存路径** = 单一事实来源（只有一份代码，永不不一致），且静态资源可直接伺服、未来无痛换对象存储。

---

## 五、为什么存磁盘就没有"文件被删了、DB 更新了文件没同步"的不一致问题？

**先承认：问得对——存磁盘并没有消灭不一致，而是把不一致从"致命的种类"降级成了"良性的种类"。**

### 方案 A（代码存 DB）的不一致：内容分叉，说不清谁是正版

存 DB 之后，用户要访问页面时你还是得把代码**写回磁盘**变成真实文件才能伺服。于是同一份代码存在**两个可写的副本**：

```
副本1：数据库 TEXT 字段
副本2：磁盘上的文件
```

从此每一次业务操作都是分叉的机会：

```
用户改了代码 → DB 更新了 → 磁盘文件还是旧的 → 用户访问页面看到旧版 ❌
运维清了磁盘 → 文件没了 → 但问题没完：DB 里的内容还算数吗？
有人直接改了磁盘文件 → DB 不知道 → 下次重新生成文件把改动覆盖了 ❌
```

死结在于：**当两边不一样时，你无法回答"哪边是对的"**——没有天然的事实来源，要靠额外的时间戳、同步标记、仲裁逻辑去猜，而这些逻辑本身还会出 bug。更糟的是，这种分叉是**日常业务操作触发的**（每次更新代码都在制造分叉机会），躲都躲不开。

### 方案 B（代码在磁盘，DB 存路径）：不一致退化成"指针悬空"

方案 B 里，**代码内容全世界只有一份**（磁盘文件），DB 行只是一张"名片"：

```
数据库行：{ id, appName, priority, path: "tmp/code_output/html_xxx/" }   ← 元数据
磁盘文件：index.html / style.css / script.js                             ← 唯一的内容
```

文件被删、DB 里的 path 指向空气的情况当然可能发生。但性质完全不同：

| 对比维度 | 方案 A 的内容分叉 | 方案 B 的指针悬空 |
|---|---|---|
| 出现问题的形式 | 两份内容不一样，**说不清谁对** | 名片指向空处，**文件在=有，不在=没** |
| 检测难度 | 需要对比内容+时间戳+仲裁 | `new File(path).exists()` 一行代码 |
| 后果 | 可能**给用户看错的内容**（错得无声无息） | 顶多"资源不存在"，404/提示已删除，**方向明确** |
| 修复方式 | 要判断保留哪份、怎么合并——没有标准答案 | 以文件为准：行没用了就删行，或重新生成 |
| 触发方式 | **日常业务操作必然触发**（每次更新都是机会） | 只有**外部异常操作**才触发（有人手动删文件、磁盘故障） |

一个类比：

> 方案 A = 把书**抄了两份**，家里一份办公室一份——任何一份被人涂改，你就说不清哪份是正版了；
> 方案 B = 书只放家里，办公室贴一张**纸条**"书在家里书架第三层"——纸条丢了或书被借走了，你查一下就知道有没有，但**书的内容永远只有一个版本**，绝不会出现"两个正版打架"。

> "避免数据库和文件存储不一致" ≠ 消灭一切不一致
> 而是 = **消灭"内容层面"的不一致**（世界上只有一份代码，永远不存在两个版本）；至于"引用悬空"（path 指向被删的文件），它依然可能发生，但它**可检测、无歧义、可修复**，是工程上能接受的良性故障。

这也是为什么全世界的 Web 都用 URL 链接而不是把目标网页整个嵌进链接里——链接可能 404，但没人因此把内容复制一份塞进每个引用处。

### 诚实清单：方案 B 剩下要处理的两件事

1. **悬空路径**：伺服/查询前 `exists()` 兜底，或定期清理"行在文件没了"的孤儿记录；
2. **写文件 + 写 DB 不是原子的**：进程在"写完文件、还没插 DB"之间崩溃，会留下无主目录（反过来先插 DB 后写文件则留下悬空行）。教程阶段忽略，生产上靠定时对账任务或调整写入顺序 + 失败补偿来收敛。

**一句话总结**：**内容只有一份，就永远不会"给用户看错版本"；指针断了顶多是"找不到"，绝不会是"给了个错的"。作者省略的就是这个"错 vs 缺"的差别。**

---

## 六、"写文件 + 写 DB 不是原子的"与无主目录问题

**关键误会点：URL（路径）和 DB 行都不是"本来就在"的，每次生成都是从零新建的。**

### 一次"生成应用"的完整时序（以本项目为例）

用户点"生成"后，后端按顺序做这些事：

```
用户请求生成
   │
   ├─ ① AI 返回代码
   │
   ├─ ② CodeFileSaverTemplate.saveCode()
   │      建新目录：tmp/code_output/html_1948xxx/（雪花ID，全新路径）
   │      写入 index.html、style.css ...          ← 此刻磁盘上有了
   │
   │      ↕ ↕ ↕  💥 如果进程在这一瞬间崩溃/断电/被 kill ↕ ↕ ↕
   │
   ├─ ③ INSERT INTO code_gen (name, priority, result_path='tmp/code_output/html_1948xxx/', ...)
   │      ← 这行还没来得及插入！
   │
   └─ ④ 返回成功
```

"URL 又没变"不成立的原因：**这条路径是 ② 里刚用雪花 ID 现生成的**，它进入数据库的唯一途径是 ③ 的 INSERT。③ 没执行，**数据库里就从来没有过这个 URL**——不是"变了"，是"压根不存在"。

### 崩溃点不同，留下的垃圾也不同

| 崩溃时机 | 磁盘 | 数据库 | 后果 |
|---|---|---|---|
| ② 完成后、③ 之前 | ✅ 目录和文件都在 | ❌ 没有对应行 | **无主目录**：没有任何行指向它 |
| （假如反过来先插 DB 再写文件）③ 后、写文件前 | ❌ 没有 | ✅ 行已在，路径悬空 | 用户点进去 404 |

"无主目录"的意思：**磁盘上躺着一套完整文件，但 DB 这个"总目录"里查不到它**——主页不会展示、没有任何 URL 路由到它、应用层面它等于不存在，除非有人猜中那个雪花 ID。它不出错、不误导用户，只是**悄悄占着磁盘**的孤儿。

### 为什么没法"原子"——事务管不了文件系统

数据库事务的回滚能力**只覆盖数据库自己的操作**：

```sql
BEGIN;
INSERT INTO code_gen ...;
ROLLBACK;   -- DB 行消失，干干净净
```

但"写文件"发生在文件系统里，**没有任何机制能让 DB 回滚时顺便删掉磁盘目录**（反过来也不行）。文件系统和数据库是两个独立系统，不存在跨两者的事务协议——所以"写文件 + 插 DB"这两步之间**永远存在一个可能被打断的窗口**，这不是写法问题，是分布式上的客观限制。

### 严重程度与生产解法（了解即可）

好消息：这类问题**不会给用户看错内容**（属于"良性故障"范畴），后果只是磁盘泄漏或偶发 404。生产的常规收敛手段：

1. **对账任务**：定时扫描磁盘目录 ↔ DB 路径，互相找不到的——无主目录删除、悬空行标记失效；
2. **状态位**：插入时先写 `status=GENERATING`，文件写完再更新 `READY`，查询只认 READY，中间态一目了然；
3. **调整顺序**：先插 DB（拿确定的主键）再写文件，把失败模式统一成"悬空行"一种，方便统一处理。

### 一句话总结

> 无主目录不是"URL 变了"，而是**路径随文件一起新建、DB 行却还没来得及插入**——磁盘先有了实体，目录册上还没登记，中间那一瞬间崩溃，它就成了查无此名的孤儿。根源是**文件系统和数据库之间不存在跨系统事务**，只能靠对账或状态机收敛。

本项目暂时还没有 ③ 这一步（`code_gen` 表还没建），教程后面加上时就会碰到这个时序问题——到时候回看这条笔记正好。

---

## 七、deployKey 部署标识字段：沙箱隔离、一词三用、短码设计

教程原文：*“最关键的是 deployKey 字段。由于每个网站应用文件的部署都是隔离的（想象成沙箱），需要用唯一字段来区分，可以作为应用的存储和访问路径；而且为了便于访问，每个应用的访问路径不能太长。”*

对照建表 SQL：`deployKey varchar(64)` + `UNIQUE KEY uk_deployKey`。

### 1. “部署隔离/沙箱”是什么意思

平台最终会同时伺服**成百上千个 AI 生成的网站**，而每个网站都是一套同构的文件（都叫 `index.html`、`style.css`...）。如果全扔在同一个目录里：

```
tmp/code_output/（所有应用混住）
├── index.html   ← A 应用的
├── index.html   ← B 应用的，直接把 A 覆盖了 ❌
```

所以每个应用必须有自己的“房间”——独立目录 + 独立 URL，互不干扰。这就是“沙箱”：**A 应用改代码、B 应用完全无感**。

```
tmp/code_output/
├── aB3xK9mQ/            ← 应用 A 的地盘
│   ├── index.html
│   └── style.css
└── xK7pQ2nW/            ← 应用 B 的地盘
    ├── index.html
    └── style.css
```

### 2. deployKey = 房间的“门牌号”，一个字段干三份活

`deployKey`（比如 `aB3xK9mQ`）是整个设计的关键，它**同时是**：

```
① 数据库里的唯一标识     UNIQUE KEY uk_deployKey
② 磁盘上的存储目录名     tmp/code_output/aB3xK9mQ/
③ 浏览器里的访问路径     http://localhost:8123/app/aB3xK9mQ/index.html
```

好处是**一词三用、全线直通**：用户访问 URL 里的 `aB3xK9mQ` → 直接映射到同名目录伺服静态文件 → 也是 DB 里那一行的查找键。不需要任何翻译层。这回答了“需要用唯一字段来区分，可以作为应用的存储和访问路径”。

### 3. 为什么不直接用雪花 ID / 自增 id 当路径？

现在的目录名是 `html_1948123456789012480`（雪花 ID）。拿它当访问路径就是：

```
http://localhost:8123/app/html_1948123456789012480/index.html
                             ↑ 又长又暴露信息
```

| 问题 | 说明 |
|---|---|
| **太长** | 19 位数字，微信分享被截断、手输不可能、日志里占地方——“为了便于访问，路径不能太长”说的就是这个 |
| **可枚举** | 自增 id 更糟：`/app/1`、`/app/2`...爬虫顺着就把全站应用爬光了。随机 deployKey 猜不中，顺带成了**弱访问控制**（知道 key 才能访问，和网盘分享链接同理） |
| **耦合** | 主键是**内部**概念，URL 是**对外**承诺。对外暴露内部 id，以后想分库分表、迁移数据，URL 全部作废；deployKey 一旦分配就永远不变 |

### 4. “不能太长”背后的权衡：短码 + 唯一索引兜底

想短，又不能撞车（两个应用同 key = 沙箱被打通了），怎么平衡？

**用大字符集的随机短码**。`varchar(64)` 是字段上限，实际生成 8 位左右的 base62 码（`a-z A-Z 0-9` 共 62 个字符）：

```
8 位 base62 的组合数 = 62⁸ ≈ 218 万亿种
```

碰撞概率低到可以忽略；万一真撞了，`UNIQUE KEY uk_deployKey` 会让 INSERT 直接报错——代码捕获后**重新生成一个再插**即可。数据库唯一索引就是最后的保险丝。

同类设计：CodePen 的 `codepen.io/pen/abc123`、jsfiddle 的短链、网盘分享码、netlify 子域名——全是“随机短码当访问路径”这一个思路。

### 5. 三轮讨论的串联：整条存储设计闭环

```
priority 字段            → 主页运营筛选（99=精选）
代码存文件系统、DB 存路径 → 单一事实来源，避免内容分叉
deployKey                → “路径”的具体形态：随机短码同时当 目录名 + URL + 唯一键
```

后续教程走向预测：用 hutool 的 `RandomUtil.randomString(8)` 生成 deployKey → 查唯一性 → 建目录 `tmp/code_output/{deployKey}/` 存文件 → 表里插入带 deployKey 的记录 → 配静态资源映射让 `/app/{deployKey}/**` 指到对应目录。












