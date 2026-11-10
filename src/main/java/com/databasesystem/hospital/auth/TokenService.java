package com.databasesystem.hospital.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class TokenService {
    public static final String ISSUER = "hospital-be";
    private final JwtEncoder encoder;
    private final Clock clock;
    private final long ttl;

    public TokenService(JwtEncoder encoder, Clock clock,
                        @Value("${hospital.security.token-ttl-seconds:1800}") long ttl) {
        if (ttl <= 0) { throw new IllegalArgumentException("Token lifetime must be positive"); }
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = ttl;
    }

    public String issue(HospitalPrincipal principal) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER)
                .subject(Long.toString(principal.accountId())).issuedAt(now).expiresAt(now.plusSeconds(ttl))
                .claim("ver", principal.tokenVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public long ttl() { return ttl; }
}
