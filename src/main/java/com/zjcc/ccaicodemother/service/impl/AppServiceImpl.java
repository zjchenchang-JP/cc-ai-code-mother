package com.zjcc.ccaicodemother.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zjcc.ccaicodemother.model.entity.App;
import com.zjcc.ccaicodemother.mapper.AppMapper;
import com.zjcc.ccaicodemother.service.AppService;
import org.springframework.stereotype.Service;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/zjchenchang-JP">CC</a>
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App>  implements AppService{

}
