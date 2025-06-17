package org.example.ticket.util.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 로거(Logger) 추가
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Value("${ticket.upload.path}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String resourcePath = "file:" + uploadDir;

        if (!uploadDir.endsWith("/") && !uploadDir.endsWith("\\")) {
            resourcePath += "/";
        }

        // ▼▼▼ 디버깅을 위한 로그 추가! ▼▼▼
        log.info("==========================================================");
        log.info("Resource Handler MAPPING");
        log.info("URL Path Pattern (웹 경로) : /picture/**");
        log.info("Physical Path (실제 파일 경로) : {}", resourcePath);
        log.info("==========================================================");

        registry.addResourceHandler("/picture/**")
                .addResourceLocations(resourcePath);
    }
}