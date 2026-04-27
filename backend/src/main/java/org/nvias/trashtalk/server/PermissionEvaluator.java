package org.nvias.trashtalk.server;

import org.nvias.trashtalk.domain.Channel;
import org.nvias.trashtalk.domain.ServerRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Vyhodnocuje, zda má daná role právo na operaci v kanálu.
 *
 * Default permissions dle role:
 *   OWNER         — vše povoleno
 *   ADMINISTRATOR — vše kromě smazání serveru / převodu vlastnictví
 *   VISITOR       — READ, WRITE, VOICE_CONNECT, VOICE_SPEAK, UPLOAD_FILE; bez MANAGE_MESSAGES
 *
 * Per-channel overrides (channel.permissionsJson):
 *   { "VISITOR": { "WRITE": false } } → VISITORům zakáže psaní jen v tomto kanálu
 */
@Component
public class PermissionEvaluator {

    public static final String READ             = "READ";
    public static final String WRITE            = "WRITE";
    public static final String VOICE_CONNECT    = "VOICE_CONNECT";
    public static final String VOICE_SPEAK      = "VOICE_SPEAK";
    public static final String UPLOAD_FILE      = "UPLOAD_FILE";
    public static final String MANAGE_MESSAGES  = "MANAGE_MESSAGES";
    public static final String MANAGE_CHANNELS  = "MANAGE_CHANNELS";
    public static final String MANAGE_MEMBERS   = "MANAGE_MEMBERS";
    public static final String MANAGE_SERVER    = "MANAGE_SERVER";
    public static final String DELETE_SERVER    = "DELETE_SERVER";
    public static final String TRANSFER_OWNER   = "TRANSFER_OWNER";

    public boolean hasPermission(ServerRole role, Channel channel, String permission) {
        boolean defaultAllowed = defaultPermission(role, permission);
        if (channel == null) return defaultAllowed;

        Map<String, Map<String, Boolean>> overrides = channel.getPermissionsJson();
        if (overrides == null || overrides.isEmpty()) return defaultAllowed;

        Map<String, Boolean> roleOverrides = overrides.get(role.name());
        if (roleOverrides == null || !roleOverrides.containsKey(permission)) return defaultAllowed;

        return roleOverrides.get(permission);
    }

    public void requirePermission(ServerRole role, Channel channel, String permission) {
        if (!hasPermission(role, channel, permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nedostatečná oprávnění: " + permission);
        }
    }

    public void requireRole(ServerRole actual, ServerRole minimum) {
        if (!actual.isAtLeast(minimum)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Vyžaduje roli " + minimum + ", máš " + actual);
        }
    }

    private boolean defaultPermission(ServerRole role, String permission) {
        return switch (role) {
            case OWNER -> true;
            case ADMINISTRATOR -> !permission.equals(DELETE_SERVER) && !permission.equals(TRANSFER_OWNER);
            case VISITOR -> switch (permission) {
                case READ, WRITE, VOICE_CONNECT, VOICE_SPEAK, UPLOAD_FILE -> true;
                default -> false;
            };
        };
    }
}
