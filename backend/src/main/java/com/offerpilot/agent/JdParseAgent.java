package com.offerpilot.agent;

import com.offerpilot.dto.JdStruct;
import com.offerpilot.llm.DeepSeekClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * JD 解析 Agent：将中文岗位描述解析为 {@link JdStruct}。
 */
@Component
public class JdParseAgent extends AbstractParseAgent<JdStruct> {

    public JdParseAgent(@Qualifier("chatModel") DeepSeekClient chatModel) {
        super(chatModel, JdStruct.class);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是招聘需求分析专家，把一段中文岗位描述（JD）解析为结构化 JSON。

                【字段】
                1. jobTitle：岗位名称；
                2. requiredSkills：技能要求，每项 {name, level}，level 只能是「必须」或「加分」；
                3. bonusItems：加分项条目；
                4. implicitRequirements：隐性要求（原文以「优先」「更佳」等方式暗示但未强制的诉求）；
                5. experienceYears：经验年限要求；
                6. educationRequirement：学历要求。

                【输出 JSON Schema（紧凑书写，无缩进与多余空格）】
                {"jobTitle":"string 或 null","requiredSkills":[{"name":"string","level":"必须 或 加分"}],"bonusItems":["string"],"implicitRequirements":["string"],"experienceYears":"string 或 null","educationRequirement":"string 或 null"}

                【输出精简要求（强制）】
                1. 字段值一律使用原文用词，禁止改写、扩写、解释、加评注或添加前缀；
                2. name ≤ 12 字；bonusItems 与 implicitRequirements 每条 ≤ 20 字；experienceYears 与 educationRequirement ≤ 20 字；
                3. requiredSkills ≤ 10 条，bonusItems ≤ 5 条，implicitRequirements ≤ 5 条，只保留原文明确提到者；
                4. 同一要求只出现一次：已写入 requiredSkills（level=加分）的技能，不得再重复写入 bonusItems；
                5. JSON 必须紧凑输出（不加缩进、换行、空格），禁止输出任何解释文字、注释或 Markdown 代码块标记。

                【反幻觉】
                只依据 JD 原文，禁止推断、补充或编造原文未出现的信息；原文未提及的字段：数组返回 []，字符串返回 null。
                """;
    }

    @Override
    protected String userPrompt(String input) {
        return """
                【待解析 JD 原文】
                <jd>
                """ + input + """
                </jd>

                【任务】
                请按【输出 JSON Schema】输出严格 JSON。
                """;
    }
}
