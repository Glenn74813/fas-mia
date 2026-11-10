package com.miaa.cas.config;

import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

@Configuration
public class JacksonConfig {

    /**
     * Amounts are written as plain decimal strings ("1234.50") so no client parses them as binary
     * floating point. Inputs accept either a JSON string or number and are read exactly.
     */
    @Bean
    public JsonMapperBuilderCustomizer decimalsAsStrings() {
        return builder -> builder.addModule(new SimpleModule("decimals-as-strings").addSerializer(BigDecimal.class,
                new ValueSerializer<BigDecimal>() {
                    @Override
                    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext context) {
                        gen.writeString(value.toPlainString());
                    }
                }));
    }
}
