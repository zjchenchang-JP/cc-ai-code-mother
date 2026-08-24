package com.zjcc.ccaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zjcc.ccaicodemother.exception.BusinessException;
import com.zjcc.ccaicodemother.exception.ErrorCode;
import com.zjcc.ccaicodemother.exception.ThrowUtils;
import com.zjcc.ccaicodemother.model.dto.user.UserQueryRequest;
import com.zjcc.ccaicodemother.model.entity.User;
import com.zjcc.ccaicodemother.mapper.UserMapper;
import com.zjcc.ccaicodemother.model.enums.UserRoleEnum;
import com.zjcc.ccaicodemother.model.vo.LoginUserVO;
import com.zjcc.ccaicodemother.model.vo.UserVO;
import com.zjcc.ccaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.zjcc.ccaicodemother.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户 服务层实现。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService{

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        ThrowUtils.throwIf(userAccount.length() < 4, ErrorCode.PARAMS_ERROR, "用户账号过短");
        if (userPassword.length() < 6 || checkPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        // 2. 检查是否重复
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号已存在");
        // 3. 密码加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 4. 插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名"); // 默认
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean result = this.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "注册失败");
        return user.getId();
    }

    @Override
    public String getEncryptPassword(String userPassword) {
        // 盐值，混淆密码
        final String SALT = "zjcc";
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1.校验
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        // 3.记录登录用户态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        return getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 判断是否已登录
        User currentUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 查数据库 确认最新状态 （追求性能的话可以直接返回上述结果）
        currentUser = this.getById(currentUser.getId());
        ThrowUtils.throwIf(currentUser == null, ErrorCode.NOT_LOGIN_ERROR);
        return currentUser;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 移除登录态即可；未登录时无需报错（登出是幂等操作，目标就是"未登录"状态）
        // 未登录的人调用登出，什么也破坏不了
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userName = userQueryRequest.getUserName();
        String userAccount = userQueryRequest.getUserAccount();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
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
    }

}
