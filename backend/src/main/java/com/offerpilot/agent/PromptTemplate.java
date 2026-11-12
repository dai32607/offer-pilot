package com.offerpilot.agent;

import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt 模板（需求说明书 7.8 Prompt 版本管理）：
 * 模板统一存放于 {@code classpath:prompts/{agent_name}.{version}.txt}，变量统一使用 <code>{{var}}</code> 占位，
 * 禁止字符串拼接构造 Prompt；版本号随调用一并记录，保证同一份产物可追溯到当时的 Prompt 版本。
 */
public final class PromptTemplate {

    private final String name;
    private final String version;
    private final String text;

    private PromptTemplate(String name, String version, String text) {
        this.name = name;
        this.version = version;
        this.text = text;
    }

    /** 从 classpath 加载模板，加载失败抛出 IllegalStateException（启动即可暴露配置缺失）。 */
    public static PromptTemplate load(String name, String version) {
        String path = "prompts/" + name + "." + version + ".txt";
        try {
            String text = new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new PromptTemplate(name, version, text);
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 模板加载失败：" + path, e);
        }
    }

    /** 渲染模板：把每个 {{key}} 替换为对应值；未提供的占位符替换为空串，避免把占位符泄漏给模型。 */
    public String render(Map<String, String> vars) {
        String result = text;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result.replaceAll("\\{\\{[a-zA-Z0-9_.]+}}", "");
    }

    public String version() {
        return version;
    }

    public String name() {
        return name;
    }
}
