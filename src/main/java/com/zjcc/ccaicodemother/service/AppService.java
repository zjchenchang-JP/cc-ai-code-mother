package com.zjcc.ccaicodemother.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.zjcc.ccaicodemother.model.dto.app.*;
import com.zjcc.ccaicodemother.model.entity.App;
import com.zjcc.ccaicodemother.model.entity.User;
import com.zjcc.ccaicodemother.model.vo.AppVO;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
public interface AppService extends IService<App> {

    /**
     * 创建应用
     *
     * @param appAddRequest 创建应用请求
     * @param loginUser     当前登录用户
     * @return 新应用 id
     */
    long addApp(AppAddRequest appAddRequest, User loginUser);

    /**
     * 修改应用（目前仅支持修改应用名称，仅本人或管理员可操作）
     *
     * @param appUpdateRequest 用户更新应用请求
     * @param loginUser        当前登录用户
     * @return 是否更新成功
     */
    boolean updateApp(AppUpdateRequest appUpdateRequest, User loginUser);

    /**
     * 更新任意应用（仅管理员，支持更新应用名称、应用封面、优先级）
     *
     * @param appEditRequest 管理员更新应用请求
     * @return 是否更新成功
     */
    boolean editApp(AppAdminUpdateRequest appEditRequest);

    /**
     * 根据 id 删除应用（仅本人或管理员可操作）
     *
     * @param id        应用 id
     * @param loginUser 当前登录用户
     * @return 是否删除成功
     */
    boolean deleteApp(long id, User loginUser);

    /**
     * 根据 id 查看应用详情（仅本人或管理员可查看）
     *
     * @param id        应用 id
     * @param loginUser 当前登录用户
     * @return 应用详情
     */
    App getAppById(long id, User loginUser);

    /**
     * 获取脱敏后的应用信息
     *
     * @param app 应用信息
     * @return 脱敏后的应用信息
     */
    AppVO getAppVO(App app);

    /**
     * 获取脱敏后的应用信息（分页）
     *
     * @param appList 应用列表
     * @return 脱敏后的应用信息列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 根据查询条件构造数据查询参数
     *
     * @param appQueryRequest 应用查询请求
     * @return 查询参数
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);
}
