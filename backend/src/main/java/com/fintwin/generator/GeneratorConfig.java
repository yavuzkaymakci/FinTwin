package com.fintwin.generator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GeneratorProperties.class)
class GeneratorConfig {
}
