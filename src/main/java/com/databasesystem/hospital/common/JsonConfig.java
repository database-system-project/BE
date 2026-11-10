package com.databasesystem.hospital.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;

@Configuration
public class JsonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictTextValues() {
        return builder -> {
            builder.deserializerByType(LocalDate.class, new JsonDeserializer<LocalDate>() {
                @Override
                public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                        return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
                    }
                    try { return Dates.parse(parser.getText()); }
                    catch (ApiException exception) {
                        throw context.weirdStringException(parser.getText(), LocalDate.class, "YYYY-MM-DD 형식의 유효한 날짜가 필요합니다.");
                    }
                }
            });
            builder.postConfigurer(mapper -> {
                var text = mapper.coercionConfigFor(LogicalType.Textual);
                text.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                text.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                text.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
        };
    }
}
