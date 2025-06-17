package org.example.ticket.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${ticket.upload.path}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String resourcePath = "file:" + uploadDir;

        if (!uploadDir.endsWith("/") && !uploadDir.endsWith("\\")) {
            resourcePath += "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(resourcePath);
    }
}