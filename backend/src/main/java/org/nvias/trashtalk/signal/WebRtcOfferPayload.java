package org.nvias.trashtalk.signal;

import lombok.Data;

@Data
public class WebRtcOfferPayload {
    private String channelId;
    private String sdp;
}
