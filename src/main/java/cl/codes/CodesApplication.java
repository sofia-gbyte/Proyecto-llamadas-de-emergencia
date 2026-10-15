package cl.codes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // habilita @Scheduled: autochequeo interno y limpieza de tokens vencidos
public class CodesApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodesApplication.class, args);
    }
}
