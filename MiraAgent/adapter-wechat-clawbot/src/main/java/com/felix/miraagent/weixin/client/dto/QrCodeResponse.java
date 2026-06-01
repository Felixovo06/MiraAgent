package com.felix.miraagent.weixin.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QrCodeResponse {
    @JsonProperty("ret")
    private int ret;

    @JsonProperty("errmsg")
    private String errmsg;

    @JsonProperty("qrcode")
    private String qrcode;

    @JsonProperty("qrcode_img_content")
    private String qrcodeImgContent;

    public boolean isError() {
        return ret != 0;
    }
}
