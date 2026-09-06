package com.zjcc.ccaicodemother.service;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 下载服务
 * 对指定路径下的文件进行打包下载
 */
public interface ProjectDownloadService {

    /**
     * 下载项目为压缩包
     *
     * @param projectPath
     * @param downloadFileName
     * @param response
     */
    void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);
}
