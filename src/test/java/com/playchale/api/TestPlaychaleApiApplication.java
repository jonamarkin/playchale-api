package com.playchale.api;

import org.springframework.boot.SpringApplication;

public class TestPlaychaleApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(PlaychaleApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
