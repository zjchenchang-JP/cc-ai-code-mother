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









