package model;

import java.util.UUID;

public record MemberInfo(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        String role,        // "OWNER" | "ADMINISTRATOR" | "VISITOR"
        String nickname
) {
    public String displayedName() {
        return (nickname != null && !nickname.isBlank()) ? nickname
                : (displayName != null ? displayName : username);
    }
}
