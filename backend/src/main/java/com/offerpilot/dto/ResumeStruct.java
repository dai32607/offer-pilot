package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 简历结构化解析结果。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeStruct(
        @JsonProperty("name") String name,
        @JsonProperty("education") List<EducationItem> education,
        @JsonProperty("skills") List<String> skills,
        @JsonProperty("certificates") List<String> certificates,
        @JsonProperty("languages") List<String> languages,
        @JsonProperty("experiences") List<ExperienceItem> experiences
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EducationItem(
            @JsonProperty("school") String school,
            @JsonProperty("major") String major,
            @JsonProperty("degree") String degree,
            @JsonProperty("period") String period
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExperienceItem(
            @JsonProperty("title") String title,
            @JsonProperty("period") String period,
            @JsonProperty("description") String description
    ) {
    }
}
