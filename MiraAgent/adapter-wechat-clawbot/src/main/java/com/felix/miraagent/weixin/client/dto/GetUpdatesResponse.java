package com.felix.miraagent.weixin.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetUpdatesResponse {
    @JsonAlias("errcode")
    @JsonProperty("ret")
    private int ret;

    @JsonProperty("errmsg")
    private String errmsg;

    @JsonProperty("get_updates_buf")
    private String getUpdatesBuf;

    @JsonProperty("msgs")
    private List<WeixinMessage> msgs;

    public boolean isError() {
        return ret != 0;
    }

    public boolean isSessionExpired() {
        return ret == -14;
    }

    public List<WeixinMessage> safeGetMsgs() {
        return msgs != null ? msgs : Collections.emptyList();
    }
}
