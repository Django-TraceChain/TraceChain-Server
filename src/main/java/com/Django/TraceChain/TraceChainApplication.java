package com.Django.TraceChain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.Django.TraceChain.component.BlockstreamProperties;

@SpringBootApplication
@EnableConfigurationProperties(BlockstreamProperties.class)
public class TraceChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceChainApplication.class, args);
    }
}
