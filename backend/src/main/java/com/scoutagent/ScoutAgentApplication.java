package com.scoutagent;

import com.scoutagent.analytics.TeamDirectory;
import com.scoutagent.data.DataSeeder;
import com.scoutagent.model.WinProbabilityService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class ScoutAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScoutAgentApplication.class, args);
    }

    /** Seed (if empty) -> load team directory -> train the model, in that order. */
    @Bean
    ApplicationRunner bootstrap(DataSeeder seeder, TeamDirectory teams, WinProbabilityService winProb) {
        return args -> {
            seeder.seedIfEmpty();
            teams.load();
            winProb.train();
        };
    }
}
