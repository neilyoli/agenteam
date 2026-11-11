package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.tool.api.ToolContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 插件凭据统一入口：优先使用插件专属的宿主凭据提供器，其余插件沿用通用连接凭据。
 */
@Service
public class JavaPluginCredentialResolver {
    private final Map<String, JavaPluginCredentialProvider> providers;
    private final HttpCredentialService credentials;

    public JavaPluginCredentialResolver(List<JavaPluginCredentialProvider> implementations,
                                         HttpCredentialService credentials) {
        Map<String, JavaPluginCredentialProvider> registered = new LinkedHashMap<>();
        for (JavaPluginCredentialProvider provider : implementations) {
            if (registered.putIfAbsent(provider.pluginId(), provider) != null) {
                throw new IllegalStateException("Java 插件凭据提供器重复注册：" + provider.pluginId());
            }
        }
        this.providers = Map.copyOf(registered);
        this.credentials = credentials;
    }

    public Map<String, String> resolve(String pluginId, ToolContext context, String credentialId) {
        JavaPluginCredentialProvider provider = providers.get(pluginId);
        if (provider != null) {
            return provider.resolve(context, credentialId);
        }
        return credentials.resolve(context.enterpriseId(), credentialId).headers();
    }
}
