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
