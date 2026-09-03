package com.kk.system.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaStrategy;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @PostConstruct
    public void rewriteSaStrategy() {
        SaStrategy.instance.setHasElement((list, element) -> {
            if (list == null || list.isEmpty() || element == null) {
                return false;
            }
            if (list.contains("*:*:*") || list.contains("*")) {
                return true;
            }
            return list.contains(element.toString());
        });
    }


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            SaRouter.match("/api/**")
                    .notMatch(
                            "/api/auth/login",
                            "/api/auth/totp/status/username",
                            "/api/auth/captcha",
                            "/api/file/preview/**",
                            "/api/file/download/**"
                    )
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
