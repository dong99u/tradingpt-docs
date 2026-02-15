package com.example.tradingpt.domain.auth.security;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.tradingpt.domain.auth.dto.response.KakaoResponse;
import com.example.tradingpt.domain.auth.dto.response.NaverResponse;
import com.example.tradingpt.domain.auth.dto.response.OAuth2Response;
import com.example.tradingpt.domain.user.entity.Customer;
import com.example.tradingpt.domain.user.entity.User;
import com.example.tradingpt.domain.user.enums.Provider;
import com.example.tradingpt.domain.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OAuth2 Social Login Integration
 *
 * Demonstrates:
 * 1. Provider-specific response parsing (Kakao/Naver)
 * 2. Auto-registration: creates Customer entity on first social login
 * 3. Provider + ProviderId composite lookup for existing accounts
 * 4. Secure random password for social-only accounts
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(request);
        var attributes = oAuth2User.getAttributes();

        // 1. Parse provider-specific response
        String providerName = request.getClientRegistration().getRegistrationId();
        OAuth2Response response = switch (providerName) {
            case "kakao" -> new KakaoResponse(attributes);
            case "naver" -> new NaverResponse(attributes);
            default -> throw new OAuth2AuthenticationException("UNSUPPORTED_PROVIDER");
        };

        Provider provider = Provider.valueOf(response.getProvider());

        // 2. Check existing linked account
        var existing = userRepository.findByProviderAndProviderId(
            provider, response.getProviderId());
        if (existing.isPresent()) {
            var user = existing.get();
            return new CustomOAuth2User(
                user.getId(), user.getUsername(), user.getRole().name(), attributes);
        }

        // 3. Auto-register new social user
        String email = response.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException("SOCIAL_EMAIL_MISSING");
        }

        User user = userRepository.save(
            Customer.builder()
                .username(response.getProvider() + "_" + response.getProviderId())
                .email(email)
                .name(response.getName())
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .provider(provider)
                .providerId(response.getProviderId())
                .build()
        );

        return new CustomOAuth2User(
            user.getId(), user.getUsername(), user.getRole().name(), attributes);
    }
}
