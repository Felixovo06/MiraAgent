package com.felix.miraagent.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mira.skill")
public class SkillProperties {
    private String baseDir = System.getProperty("user.home") + "/.miraagent/skills";
}
