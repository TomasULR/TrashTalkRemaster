package server;

import model.ChannelInfo;
import model.MemberInfo;
import model.ServerInfo;
import net.ApiClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServerApiService {

    private final ApiClient client;

    public ServerApiService(ApiClient client) {
        this.client = client;
    }

    // ---- Servers ----

    public List<ServerInfo> listMyServers() throws IOException {
        return client.get("/api/servers", ServerInfoList.class);
    }

    public ServerInfo createServer(String name) throws IOException {
        return client.post("/api/servers", Map.of("name", name), ServerInfo.class);
    }

    public ServerInfo updateServer(UUID serverId, String newName) throws IOException {
        return client.patch("/api/servers/" + serverId, Map.of("name", newName), ServerInfo.class);
    }

    public void deleteServer(UUID serverId) throws IOException {
        client.delete("/api/servers/" + serverId);
    }

    public ServerInfo joinByInvite(String code) throws IOException {
        return client.post("/api/servers/join/" + code, Map.of(), ServerInfo.class);
    }

    public void leaveServer(UUID serverId) throws IOException {
        client.delete("/api/servers/" + serverId + "/leave");
    }

    // ---- Members ----

    public List<MemberInfo> listMembers(UUID serverId) throws IOException {
        return client.get("/api/servers/" + serverId + "/members", MemberInfoList.class);
    }

    public MemberInfo setMemberRole(UUID serverId, UUID userId, String role) throws IOException {
        return client.patch("/api/servers/" + serverId + "/members/" + userId + "/role",
                Map.of("role", role), MemberInfo.class);
    }

    public void kickMember(UUID serverId, UUID userId) throws IOException {
        client.delete("/api/servers/" + serverId + "/members/" + userId);
    }

    // ---- Channels ----

    public List<ChannelInfo> listChannels(UUID serverId) throws IOException {
        return client.get("/api/servers/" + serverId + "/channels", ChannelInfoList.class);
    }

    public ChannelInfo createChannel(UUID serverId, String name, String type,
                                     String topic, Integer bitrateKbps) throws IOException {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("type", type);
        if (topic != null) body.put("topic", topic);
        if (bitrateKbps != null) body.put("voiceBitrateKbps", bitrateKbps);
        return client.post("/api/servers/" + serverId + "/channels", body, ChannelInfo.class);
    }

    public void deleteChannel(UUID channelId) throws IOException {
        client.delete("/api/channels/" + channelId);
    }

    // ---- Invites ----

    public record InviteResult(String code, String serverName, Integer maxUses, int uses) {}

    public InviteResult createInvite(UUID serverId, Integer maxUses, Integer expiresInHours) throws IOException {
        var body = new HashMap<String, Object>();
        if (maxUses != null) body.put("maxUses", maxUses);
        if (expiresInHours != null) body.put("expiresInHours", expiresInHours);
        return client.post("/api/servers/" + serverId + "/invites", body, InviteResult.class);
    }

    // Jackson type tokens for List deserialization
    static class ServerInfoList  extends java.util.ArrayList<ServerInfo>  {}
    static class MemberInfoList  extends java.util.ArrayList<MemberInfo>  {}
    static class ChannelInfoList extends java.util.ArrayList<ChannelInfo> {}
}
