package com.felix.miraagent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ChatApiRequest {
    private String userId;
    private String sessionId;
    private String characterId;
    private String content;
    private List<String> enabledTools;
    /** 上传到工作区的图片文件名，本轮内联为多模态图片发给模型。 */
    private List<String> images;
    @JsonProperty("stream")
    private boolean stream;
}
