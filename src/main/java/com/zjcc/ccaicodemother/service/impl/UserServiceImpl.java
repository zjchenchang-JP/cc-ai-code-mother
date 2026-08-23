package com.zjcc.ccaicodemother.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zjcc.ccaicodemother.model.entity.User;
import com.zjcc.ccaicodemother.mapper.UserMapper;
import com.zjcc.ccaicodemother.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户 服务层实现。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService{

}
