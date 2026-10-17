package se.rightech.fakturummetsarkiv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FakturummetsArkivApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakturummetsArkivApplication.class, args);
    }

}
