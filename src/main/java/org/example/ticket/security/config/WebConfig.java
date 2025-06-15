package org.example.ticket.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // application.yml 또는 properties 파일에 설정한 파일 업로드 경로를 주입받습니다.
    @Value("${ticket.upload.path}")
    private String fileUploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // "/picture/**" URL 요청이 오면
        registry.addResourceHandler("/picture/**")
                // file:///home/ubuntu/capstoneBackend/pathForPhoto/picture/ 에서 파일을 찾아 제공합니다.
                .addResourceLocations("file:///" + fileUploadDir + "/picture/");
    }
}