package org.nvias.trashtalk.signal;

import lombok.Data;

@Data
public class WebRtcIcePayload {
    private String channelId;
    private String candidate;
    private String sdpMid;
    private int sdpMLineIndex;
}
