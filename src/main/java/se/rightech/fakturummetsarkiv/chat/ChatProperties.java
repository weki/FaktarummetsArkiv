package se.rightech.fakturummetsarkiv.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "chat")
public record ChatProperties(
        @DefaultValue("0.1") double answerTemperature,
        @DefaultValue("true") boolean thinking,
        @DefaultValue("en") String answerLanguage) {
}
