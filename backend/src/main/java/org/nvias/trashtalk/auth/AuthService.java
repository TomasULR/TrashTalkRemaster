package org.nvias.trashtalk.auth;

import org.nvias.trashtalk.auth.dto.*;
import org.nvias.trashtalk.domain.RefreshToken;
import org.nvias.trashtalk.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${trashtalk.jwt.access-token-ttl-minutes:60}")
    private long accessTokenTtlMinutes;

    @Value("${trashtalk.jwt.refresh-token-ttl-days:7}")
    private long refreshTokenTtlDays;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordHasher passwordHasher,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByUsernameIgnoreCase(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Uživatelské jméno je již obsazeno");
        }
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail je již registrován");
        }

        String hash = passwordHasher.hash(req.password().toCharArray());
        User user = new User(req.username().toLowerCase(), req.email().toLowerCase(), hash);
        user = userRepository.save(user);

        return issueTokenPair(user, null);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository
                .findByUsernameIgnoreCase(req.usernameOrEmail())
                .or(() -> userRepository.findByEmailIgnoreCase(req.usernameOrEmail()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Neplatné přihlašovací údaje"));

        if (!passwordHasher.verify(user.getPasswordHash(), req.password().toCharArray())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Neplatné přihlašovací údaje");
        }

        return issueTokenPair(user, req.deviceInfo());
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest req) {
        String tokenHash = sha256Hex(req.refreshToken());
        RefreshToken rt = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Neplatný refresh token"));

        if (!rt.isValid()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token vypršel nebo byl odvolán");
        }

        rt.setRevokedAt(Instant.now());
        refreshTokenRepository.save(rt);

        return issueTokenPair(rt.getUser(), rt.getDeviceInfo());
    }

    @Transactional
    public void logout(String userId) {
        refreshTokenRepository.revokeAllForUser(
                java.util.UUID.fromString(userId), Instant.now());
    }

    // ---- helpers ----

    private AuthResponse issueTokenPair(User user, String deviceInfo) {
        String accessToken = jwtService.generateAccessToken(user);

        byte[] rawToken = new byte[32];
        secureRandom.nextBytes(rawToken);
        String refreshTokenRaw = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);

        RefreshToken rt = new RefreshToken(
                user,
                sha256Hex(refreshTokenRaw),
                deviceInfo,
                Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS)
        );
        refreshTokenRepository.save(rt);

        return new AuthResponse(
                accessToken,
                refreshTokenRaw,
                accessTokenTtlMinutes * 60,
                user.getId(),
                user.getUsername(),
                user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                user.getAvatarUrl()
        );
    }

    private String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
