package com.zjcc.ccaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zjcc.ccaicodemother.constant.UserConstant;
import com.zjcc.ccaicodemother.exception.BusinessException;
import com.zjcc.ccaicodemother.exception.ErrorCode;
import com.zjcc.ccaicodemother.exception.ThrowUtils;
import com.zjcc.ccaicodemother.mapper.AppMapper;
import com.zjcc.ccaicodemother.model.dto.app.*;
import com.zjcc.ccaicodemother.model.entity.App;
import com.zjcc.ccaicodemother.model.entity.User;
import com.zjcc.ccaicodemother.model.enums.CodeGenTypeEnum;
import com.zjcc.ccaicodemother.model.vo.AppVO;
import com.zjcc.ccaicodemother.model.vo.UserVO;
import com.zjcc.ccaicodemother.service.AppService;
import com.zjcc.ccaicodemother.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

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
        app.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());
        // 3. 插入数据
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建应用失败");
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

    /**
     * 判断用户是否为管理员
     *
     * @param user 用户信息
     * @return 是否为管理员
     */
    private boolean isAdmin(User user) {
        return UserConstant.ADMIN_ROLE.equals(user.getUserRole());
    }

}
