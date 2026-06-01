package com.felix.miraagent.tools.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

public class FileToolResultCache implements ToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(FileToolResultCache.class);
    private static final String URI_SCHEME = "artifact://tool_result/";

    private final ArtifactProperties props;
    private final ObjectMapper objectMapper;

    public FileToolResultCache(ArtifactProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String store(ToolResultArtifact artifact) {
        File dir = new File(props.getBaseDir(), "tool_result");
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, artifact.getArtifactId() + ".json");
            objectMapper.writeValue(file, artifact);
        } catch (Exception e) {
            log.warn("Failed to write artifact {} to disk", artifact.getArtifactId(), e);
        }
        return URI_SCHEME + artifact.getArtifactId();
    }

    @Override
    public Optional<ToolResultArtifact> load(String uri) {
        if (uri == null || !uri.startsWith(URI_SCHEME)) {
            return Optional.empty();
        }
        String artifactId = uri.substring(URI_SCHEME.length());
        File file = new File(new File(props.getBaseDir(), "tool_result"), artifactId + ".json");
        if (!file.exists()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file, ToolResultArtifact.class));
        } catch (Exception e) {
            log.warn("Failed to read artifact {} from disk", artifactId, e);
            return Optional.empty();
        }
    }
}
