package com.quickbooks.biznetwork.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TraversalProperties.class, PathSearchProperties.class, AiResolutionProperties.class})
public class AppConfig {
}
