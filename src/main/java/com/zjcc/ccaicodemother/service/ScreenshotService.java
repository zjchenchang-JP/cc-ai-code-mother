package com.zjcc.ccaicodemother.service;

/**
 * 截图服务
 * 将截图功能单独封装为一个 通用服务，将本地生成截图和文件上传整合在一起
 * 根据要截图的网址 返回截图后的图片访问地址
 */
public interface ScreenshotService {


    /**
     * 通用的截图服务，可以得到访问地址
     *
     * @param webUrl 网址
     * @return
     */
    String generateAndUploadScreenshot(String webUrl);

}
