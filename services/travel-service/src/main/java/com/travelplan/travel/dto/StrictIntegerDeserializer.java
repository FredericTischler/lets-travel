package com.travelplan.travel.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Accepts only a JSON integer literal for an {@link Integer} field.
 *
 * Jackson's default is to silently coerce {@code 4.7} to {@code 4} and
 * {@code "4"} to {@code 4}. For a rating that is a quiet data-corruption
 * path (a client sending 4.7 would be recorded as 4 with no error), so this
 * DTO opts out per field — the rest of the API's lenient Jackson behaviour is
 * left untouched. Anything that is not a plain integer token is rejected with
 * a Jackson mapping error, which Spring surfaces as HTTP 400.
 */
class StrictIntegerDeserializer extends StdDeserializer<Integer> {

    StrictIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
