package com.zjcc.ccaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zjcc.ccaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zjcc.ccaicodemother.constant.AppConstant;
import com.zjcc.ccaicodemother.constant.UserConstant;
import com.zjcc.ccaicodemother.core.AiCodeGeneratorFacade;
import com.zjcc.ccaicodemother.core.builder.VueProjectBuilder;
import com.zjcc.ccaicodemother.core.handler.StreamHandlerExecutor;
import com.zjcc.ccaicodemother.exception.BusinessException;
import com.zjcc.ccaicodemother.exception.ErrorCode;
import com.zjcc.ccaicodemother.exception.ThrowUtils;
import com.zjcc.ccaicodemother.mapper.AppMapper;
import com.zjcc.ccaicodemother.model.dto.app.*;
import com.zjcc.ccaicodemother.model.entity.App;
import com.zjcc.ccaicodemother.model.entity.User;
import com.zjcc.ccaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zjcc.ccaicodemother.model.enums.CodeGenTypeEnum;
import com.zjcc.ccaicodemother.model.vo.AppVO;
import com.zjcc.ccaicodemother.model.vo.UserVO;
import com.zjcc.ccaicodemother.service.AppService;
import com.zjcc.ccaicodemother.service.ChatHistoryService;
import com.zjcc.ccaicodemother.service.ScreenshotService;
import com.zjcc.ccaicodemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;
    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Override
    public long addApp(AppAddRequest appAddRequest, User loginUser) {
        // 1. 校验
        if (appAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String initPrompt = appAddRequest.getInitPrompt();
        // 创建应用必须填写 initPrompt
        if (StrUtil.isBlank(initPrompt)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "initPrompt 不能为空");
        }
        // 2. 填充实体
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 默认应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 暂时设置为多文件生成
        //app.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());
        // 暂时设置为 VUE 工程生成
        //app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());

        // 使用AI 智能选择代码生成类型
        // 应用创建时会自动调用智能路由服务，根据用户的初始提示词选择最合适的代码生成类型
        CodeGenTypeEnum selectedCodeGenType  = aiCodeGenTypeRoutingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType .getValue());
        // 3. 插入数据
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建应用失败");
        log.info("应用创建成功，ID: {}，类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app.getId();
    }

    @Override
    public boolean updateApp(AppUpdateRequest appUpdateRequest, User loginUser) {
        // 1. 校验
        if (appUpdateRequest == null || appUpdateRequest.getId() == null || appUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String appName = appUpdateRequest.getAppName();
        if (StrUtil.isBlank(appName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        }
        if (appName.length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称过长");
        }
        // 2. 查询旧数据，校验是否存在
        App oldApp = this.getById(appUpdateRequest.getId());
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 3. 仅本人可修改
        if (!oldApp.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 4. 更新（目前仅支持修改应用名称）
        App app = new App();
        app.setId(appUpdateRequest.getId());
        app.setAppName(appName);
        // 手动设置了 editTime，为区分 用户主动编辑 和 系统自动更新
        app.setEditTime(LocalDateTime.now());
        return this.updateById(app);
    }

    @Override
    public boolean editApp(AppAdminUpdateRequest adminEditRequest) {
        // 1. 校验
        if (adminEditRequest == null || adminEditRequest.getId() == null || adminEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (StrUtil.isNotBlank(adminEditRequest.getAppName()) && adminEditRequest.getAppName().length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称过长");
        }
        // 2. 更新（支持更新应用名称、应用封面、优先级）
        App app = new App();
        BeanUtil.copyProperties(adminEditRequest, app);
        app.setEditTime(LocalDateTime.now());
        return this.updateById(app);
    }

    @Override
    public boolean deleteApp(long id, User loginUser) {
        // 1. 校验
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 2. 查询旧数据，校验是否存在
        App oldApp = this.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 3. 仅本人或管理员可删除
        if (!oldApp.getUserId().equals(loginUser.getId()) && !isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return this.removeById(id);
    }

    /**
     * 删除应用时关联删除对话历史
     *
     * @param id 应用ID
     * @return 是否成功
     */
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        // 转换为 Long 类型
        Long appId = Long.valueOf(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史
        try {
            chatHistoryService.deleteByAppId(appId);
        } catch (Exception e) {
            // 采用了容错设计 不用事务
            // 即使对话历史删除失败，也不会阻止应用的删除操作，只是记录错误日志，确保核心业务的稳定性
            log.error("删除应用关联对话历史失败: {}", e.getMessage());
        }
        // 删除应用
        return super.removeById(id);
    }


    @Override
    public App getAppById(long id, User loginUser) {
        // 1. 校验
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 2. 查询数据，校验是否存在
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 3. 应用包含 initPrompt 等敏感信息，仅本人或管理员可查看
        if (!app.getUserId().equals(loginUser.getId()) && !isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return app;
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息 避免 N+1 查询
        Set<Long> userIds = appList.stream().map(App::getUserId).collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = new QueryWrapper();
        // id 为 null 时 MyBatis Flex 自动忽略该条件；空串则必须手动排除，否则会拼出 codeGenType = ''
        queryWrapper.eq("id", id);
        queryWrapper.eq("priority", priority);
        queryWrapper.eq("userId", userId);
        if (StrUtil.isNotBlank(appName)) {
            queryWrapper.like("appName", appName);
        }
        if (StrUtil.isNotBlank(cover)) {
            queryWrapper.like("cover", cover);
        }
        if (StrUtil.isNotBlank(initPrompt)) {
            queryWrapper.like("initPrompt", initPrompt);
        }
        if (StrUtil.isNotBlank(codeGenType)) {
            queryWrapper.eq("codeGenType", codeGenType);
        }
        if (StrUtil.isNotBlank(deployKey)) {
            queryWrapper.eq("deployKey", deployKey);
        }
        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        }
        return queryWrapper;
    }

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        // 5. 通过校验后，添加用户消息到对话历史
        // 在调用 AI 前，先保存用户消息到数据库中
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(),
                loginUser.getId());
        // 6. 调用 AI 生成代码（流式）
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
        // 7. 收集AI响应内容并在完成后记录到对话历史
        return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App oldApp = this.getById(appId);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限部署该应用，仅本人可以部署
        ThrowUtils.throwIf(!oldApp.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "无权限部署应用");
        // 4. 检查是否已有 deployKey
        String deployKey = oldApp.getDeployKey();
        // 没有则生成 6 位 deployKey（大小写字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，构建源目录路径
        String codeGenType = oldApp.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 6. 检查源目录是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
        // 7. Vue项目特殊处理 执行构建 再部署
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (CodeGenTypeEnum.VUE_PROJECT.equals(codeGenTypeEnum)) {
            // Vue 项目需要构建 此处场景是用户明确点击部署，不能用异步构建 要实时看到进度
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            // 检查 dist 目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 构建完成后，需要将构建后的 dist文件 复制到部署目录
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }
        // 8. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        // 9. 更新应用的 deployKey 和部署时间
        App app = new App();
        app.setDeployKey(deployKey);
        app.setDeployedTime(LocalDateTime.now());
        app.setId(appId);
        boolean result = this.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 10. 构建应用访问 URL
        String appDeployUrl = String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);
        // 11. 异步生成截图并更新应用封面
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;
    }

    /**
     * 判断用户是否为管理员
     *
     * @param user 用户信息
     * @return 是否为管理员
     */
    private boolean isAdmin(User user) {
        return UserConstant.ADMIN_ROLE.equals(user.getUserRole());
    }

    /**
     * 异步生成应用截图并更新封面
     * 截图生成是一个相对耗时的操作，我们必须使用异步方式处理，避免阻塞用户的部署流程
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程异步执行
        Thread.startVirtualThread(() -> {
            // 调用截图服务生成截图并上传
            String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
            // 更新数据库的封面字段
            App app = new App();
            app.setId(appId);
            app.setCover(screenshotUrl);
            boolean result = this.updateById(app);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
        });
    }

}
