package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * JD（岗位描述）结构化解析结果。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JdStruct(
        @JsonProperty("jobTitle") String jobTitle,
        @JsonProperty("requiredSkills") List<SkillItem> requiredSkills,
        @JsonProperty("bonusItems") List<String> bonusItems,
        @JsonProperty("implicitRequirements") List<String> implicitRequirements,
        @JsonProperty("experienceYears") String experienceYears,
        @JsonProperty("educationRequirement") String educationRequirement
) {

    /**
     * 技能项：level 取值「必须」或「加分」。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillItem(
            @JsonProperty("name") String name,
            @JsonProperty("level") String level
    ) {
    }
}
