package dev.recall.security;

import dev.recall.service.TokenService;
import java.util.List;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthUser.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                    WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new AuthenticationCredentialsNotFoundException("No JWT on this request");
        }
        return fromJwt(token.getToken());
    }

    static AuthUser fromJwt(Jwt jwt) {
        String team = jwt.getClaimAsString(TokenService.TEAM_CLAIM);
        if (jwt.getSubject() == null || team == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT lacks subject or team claim");
        }
        return new AuthUser(UUID.fromString(jwt.getSubject()), UUID.fromString(team),
                jwt.getClaimAsString(TokenService.NAME_CLAIM));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
