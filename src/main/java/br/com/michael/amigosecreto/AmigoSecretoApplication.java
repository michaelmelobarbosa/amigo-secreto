package br.com.michael.amigosecreto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AmigoSecretoApplication {

	public static void main(String[] args) {
		SpringApplication.run(AmigoSecretoApplication.class, args);
	}

}
