package com.chj.config;

import com.chj.interceptors.LoginInterceptor;
import com.chj.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).excludePathPatterns("/user/login","/user/register");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 启动时将 token 绝对有效期（absoluteExpiryHours）桥接到 JwtUtil 的静态字段。
     */
    @Bean
    public Object jwtUtilTokenExpiryConfigurer(TokenProperties tokenProperties) {
        JwtUtil.setTokenExpiryMs(tokenProperties.getAbsoluteExpiryHours() * 3600 * 1000);
        return new Object();
    }
}
