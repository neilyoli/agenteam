package com.stonewu.agenteam.service.plugin;

import com.stonewu.tool.api.ToolContext;

import java.util.Map;

/**
 * 插件专属凭据解析契约。凭据由宿主按本次执行身份解析，不进入 Agent 参数或插件静态配置。
 */
public interface JavaPluginCredentialProvider {

    /**
     * 返回该凭据提供器负责的插件 ID。
     */
    String pluginId();

    /**
     * 根据宿主核定的本次执行上下文解析请求头。
     */
    Map<String, String> resolve(ToolContext context, String credentialId);
}
