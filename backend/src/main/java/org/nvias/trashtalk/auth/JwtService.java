package org.nvias.trashtalk.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.nvias.trashtalk.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    @Value("${trashtalk.jwt.issuer:trashtalk}")
    private String issuer;

    @Value("${trashtalk.jwt.access-token-ttl-minutes:60}")
    private long accessTokenTtlMinutes;

    @Value("${trashtalk.jwt.private-key-path}")
    private String privateKeyPath;

    @Value("${trashtalk.jwt.public-key-path}")
    private String publicKeyPath;

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private JwtParser parser;

    @PostConstruct
    void init() throws Exception {
        privateKey = loadPrivateKey(privateKeyPath);
        publicKey  = loadPublicKey(publicKeyPath);
        parser = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(issuer)
                .build();
        log.info("JWT RS256 keys loaded from {} / {}", privateKeyPath, publicKeyPath);
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlMinutes * 60)))
                .signWith(privateKey)
                .compact();
    }

    public Claims parseAccessToken(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseAccessToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractUserId(String token) {
        return parseAccessToken(token).getSubject();
    }

    // ---- PEM helpers ----

    private PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private PublicKey loadPublicKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path))
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}
