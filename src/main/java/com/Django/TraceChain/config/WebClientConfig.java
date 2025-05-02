package com.Django.TraceChain.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    // Define a WebClient Bean to be used in the application
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();  // Return a WebClient.Builder instance
    }
}
