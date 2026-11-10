package com.databasesystem.hospital.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccountJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AccountRepository accounts;

    public AccountJwtConverter(AccountRepository accounts) { this.accounts = accounts; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            HospitalPrincipal principal = accounts.active(Long.parseLong(jwt.getSubject()))
                    .orElseThrow(AccountJwtConverter::invalid);
            Object version = jwt.getClaim("ver");
            if (!(version instanceof Number number) || number.longValue() != principal.tokenVersion()) {
                throw invalid();
            }
            return UsernamePasswordAuthenticationToken.authenticated(principal, jwt.getTokenValue(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
