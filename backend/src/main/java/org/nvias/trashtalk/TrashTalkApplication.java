package org.nvias.trashtalk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TrashTalkApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrashTalkApplication.class, args);
    }
}
