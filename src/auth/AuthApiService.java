package auth;

import net.ApiClient;
import net.ApiClient.ApiException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Volá /api/auth/* endpointy backendu. */
public class AuthApiService {

    private final ApiClient client;

    public AuthApiService(ApiClient client) {
        this.client = client;
    }

    public AuthResponse login(String usernameOrEmail, String password) throws IOException {
        record LoginReq(String usernameOrEmail, String password, String deviceInfo) {}
        return client.post("/api/auth/login",
                new LoginReq(usernameOrEmail, password, "TrashTalk Desktop"), AuthResponse.class);
    }

    public AuthResponse register(String username, String email, String password) throws IOException {
        record RegisterReq(String username, String email, String password) {}
        return client.post("/api/auth/register",
                new RegisterReq(username, email, password), AuthResponse.class);
    }

    public AuthResponse refresh(String refreshToken) throws IOException {
        record RefreshReq(String refreshToken) {}
        return client.post("/api/auth/refresh",
                new RefreshReq(refreshToken), AuthResponse.class);
    }

    public void logout() throws IOException {
        client.postNoBody("/api/auth/logout");
    }

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UUID userId,
            String username,
            String displayName,
            String avatarUrl
    ) {}
}
