package com.david.pingr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PingrApplication {

	public static void main(String[] args) {

		SpringApplication.run(PingrApplication.class, args);

	}

}
