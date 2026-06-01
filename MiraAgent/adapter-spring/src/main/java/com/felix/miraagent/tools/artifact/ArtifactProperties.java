package com.felix.miraagent.tools.artifact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mira.artifact")
public class ArtifactProperties {
    private String baseDir = System.getProperty("user.home") + "/.miraagent/artifacts";
}
