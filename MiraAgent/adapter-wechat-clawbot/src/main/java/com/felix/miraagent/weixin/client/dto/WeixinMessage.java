package com.felix.miraagent.weixin.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeixinMessage {
    @JsonProperty("from_user_id")
    private String fromUserId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("context_token")
    private String contextToken;

    @JsonProperty("item_list")
    private List<MessageItem> itemList;
}
