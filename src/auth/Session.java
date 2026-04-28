package auth;

import java.util.UUID;

/** Drží přihlašovací stav pro celou dobu běhu aplikace (singleton). */
public final class Session {

    private static final Session INSTANCE = new Session();
    public static Session get() { return INSTANCE; }

    private String accessToken;
    private String refreshToken;
    private UUID userId;
    private String username;
    private String displayName;
    private String avatarUrl;

    private Session() {}

    public void set(net.ApiClient client,
                    String accessToken, String refreshToken,
                    UUID userId,
                    String username, String displayName, String avatarUrl) {
        this.accessToken    = accessToken;
        this.refreshToken   = refreshToken;
        this.userId         = userId;
        this.username       = username;
        this.displayName    = displayName;
        this.avatarUrl      = avatarUrl;
        client.setAccessToken(accessToken);
    }

    public void clear(net.ApiClient client) {
        accessToken = refreshToken = username = displayName = avatarUrl = null;
        userId = null;
        client.clearAccessToken();
    }

    public boolean isLoggedIn()       { return accessToken != null; }
    public String getAccessToken()    { return accessToken; }
    public String getRefreshToken()   { return refreshToken; }
    public UUID getUserId()           { return userId; }
    public String getUsername()       { return username; }
    public String getDisplayName()    { return displayName; }
    public String getAvatarUrl()      { return avatarUrl; }
}
