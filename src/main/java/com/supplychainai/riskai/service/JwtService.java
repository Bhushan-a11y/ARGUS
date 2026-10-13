package com.supplychainai.riskai.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.supplychainai.riskai.entity.Supplier;

@Service 
public class JwtService {
      private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final long expirationSeconds;

    public JwtService(
            JwtEncoder jwtEncoder,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.expiration-seconds:3600}")
            long expirationSeconds) {

        if (expirationSeconds < 60 || expirationSeconds > 86400) {
            throw new IllegalArgumentException(
                    "JWT lifetime must be between 60 and 86400 seconds");
        }

        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.expirationSeconds = expirationSeconds;
    }

    public IssuedToken generateToken(Supplier supplier) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirationSeconds);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(supplier.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", supplier.getEmail())
                .claim("name", supplier.getName())
                .claim("role", supplier.getRole().name())
                .claim("authProvider",
                        supplier.getAuthProvider().name())
                .claim("verificationStatus",
                        supplier.getVerificationStatus().name())
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();

        return new IssuedToken(token, expiresAt, expirationSeconds);
    }

    public record IssuedToken(
            String accessToken,
            Instant expiresAt,
            long expiresInSeconds) {
    }

}
