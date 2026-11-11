package com.stonewu.agenteam.service.skill;

import java.util.List;

/**
 * 内置技能的结构化声明：提示词只注入模型输入，不承载可执行代码；插件依赖固定为平台内置插件。
 */
public record BuiltinSkillAdapter(
    String code,
    String name,
    String description,
    String icon,
    String color,
    String scenario,
    String inputDescription,
    String instructions,
    String outputDescription,
    String example,
    boolean showInWorkspace,
    List<String> pluginCodes) {
}
