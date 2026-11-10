package com.databasesystem.hospital.config;

import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod method) {
                    QueryParameters annotation = method.getMethodAnnotation(QueryParameters.class);
                    Set<String> allowed = annotation == null ? Set.of() : Set.copyOf(Arrays.asList(annotation.value()));
                    request.getParameterMap().forEach((name, values) -> {
                        if (!allowed.contains(name) || values.length != 1) { throw ApiException.badRequest(); }
                    });
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
}
