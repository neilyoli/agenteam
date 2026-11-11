package com.stonewu.agenteam.service.skill;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置技能只能引用显式登记的适配器，代码编号不当作可执行内容。
 */
@Component
public class BuiltinSkillRegistry {
    private final Map<String, BuiltinSkillAdapter> adapters = new LinkedHashMap<>();

    public BuiltinSkillRegistry(List<BuiltinSkillAdapter> registered) {
        for (var adapter : registered) {
            if (adapters.put(adapter.code(), adapter) != null) {
                throw new IllegalStateException("内置技能代码重复登记：" + adapter.code());
            }
        }
    }

    public BuiltinSkillAdapter require(String code) {
        var adapter = adapters.get(code);
        if (adapter == null) {
            throw ApiException.invalidField("builtinCode", "请选择当前支持的内置技能。");
        }
        return adapter;
    }

    public List<BuiltinSkillAdapter> list() {
        return List.copyOf(adapters.values());
    }
}
