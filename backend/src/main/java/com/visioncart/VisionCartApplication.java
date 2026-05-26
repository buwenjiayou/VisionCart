package com.visioncart;

import com.visioncart.config.VisionCartProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(VisionCartProperties.class)
public class VisionCartApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisionCartApplication.class, args);
    }
}
