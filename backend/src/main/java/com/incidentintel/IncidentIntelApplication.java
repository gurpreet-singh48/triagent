package com.incidentintel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IncidentIntelApplication {
    public static void main(String[] args) {
        SpringApplication.run(IncidentIntelApplication.class, args);
    }
}
