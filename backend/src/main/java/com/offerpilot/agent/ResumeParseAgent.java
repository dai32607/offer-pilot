package com.offerpilot.agent;

import com.offerpilot.dto.ResumeStruct;
import com.offerpilot.llm.DeepSeekClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 简历解析 Agent：将简历原文解析为 {@link ResumeStruct}。
 */
@Component
public class ResumeParseAgent extends AbstractParseAgent<ResumeStruct> {

    public ResumeParseAgent(@Qualifier("chatModel") DeepSeekClient chatModel) {
        super(chatModel, ResumeStruct.class);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是简历信息抽取专家，把一段简历原文解析为结构化 JSON。

                【字段】
                1. name：姓名；
                2. education：教育经历，每项 {school, major, degree, period}；
                3. skills：技能条目；
                4. certificates：证书条目；
                5. languages：语言能力条目；
                6. experiences：经历，每项 {title, period, description}。

                【输出 JSON Schema（紧凑书写，无缩进与多余空格）】
                {"name":"string 或 null","education":[{"school":"string","major":"string","degree":"string","period":"string 或 null"}],"skills":["string"],"certificates":["string"],"languages":["string"],"experiences":[{"title":"string","period":"string 或 null","description":"string"}]}

                【输出精简要求（强制）】
                1. 字段值一律使用原文用词，禁止改写、概括扩写、加评注或补充说明；
                2. skills 每条 ≤ 12 字、上限 20 条；certificates 与 languages 每条 ≤ 20 字、各上限 5 条；degree、period 使用原文表述（如「本科在读（大三）」「2023 年至今」）；
                3. experiences 的 description ≤ 60 字，只写原文所述职责要点，不展开、不渲染、不逐句抄写；
                4. 原文如实标注「暂无」「无」的内容不视为有效信息，不写入对应列表；
                5. JSON 必须紧凑输出（不加缩进、换行、空格），禁止输出任何解释文字、注释或 Markdown 代码块标记。

                【反幻觉】
                只依据简历原文，禁止推断、补充或编造原文未出现的信息（例如不得补全未提及的获奖、项目或技能）；原文未提及的字段：数组返回 []，字符串返回 null。
                """;
    }

    @Override
    protected String userPrompt(String input) {
        return """
                【待解析简历原文】
                <resume>
                """ + input + """
                </resume>

                【任务】
                请按【输出 JSON Schema】输出严格 JSON。
                """;
    }
}
