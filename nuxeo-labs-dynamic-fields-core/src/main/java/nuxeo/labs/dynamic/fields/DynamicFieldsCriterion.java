/*
 * (C) Copyright 2025 Hyland (http://hyland.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.labs.dynamic.fields;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.api.NuxeoException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A single search criterion on a dynamic field, as submitted through the {@code dynf_search} named parameter.
 * <p>
 * The expected JSON payload is an array of flat objects:
 *
 * <pre>
 * [{"fieldName":"color","fieldTyp":"string","value":"blue","operator":"eq"}]
 * </pre>
 *
 * {@code operator} is optional and defaults to {@code eq}.
 *
 * @since 2025.3
 */
public record DynamicFieldsCriterion(String fieldName, FieldType fieldType, Operator operator, String value) {

    /** The nested path in the OpenSearch index. */
    public static final String NESTED_PATH = "dynf:values";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The supported dynamic field types, mirroring the {@code dynf_field_types} vocabulary.
     * <p>
     * {@code blob} is deliberately absent: a blob has no searchable scalar column in the OpenSearch mapping.
     */
    public enum FieldType {

        STRING("stringValue"),

        INTEGER("integerValue"),

        DOUBLE("doubleValue"),

        BOOLEAN("booleanValue"),

        DATE("dateValue");

        private final String column;

        FieldType(String column) {
            this.column = column;
        }

        /** The OpenSearch field holding the value for this type, relative to the nested path. */
        public String column() {
            return NESTED_PATH + "." + column;
        }

        public static FieldType parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                if ("blob".equalsIgnoreCase(value)) {
                    throw new NuxeoException("Blob dynamic fields cannot be searched: " + value, e);
                }
                throw new NuxeoException("Unsupported dynamic field type: " + value, e);
            }
        }
    }

    /** The supported comparison operators. */
    public enum Operator {

        EQ, LIKE, LT, LTE, GT, GTE;

        public static Operator parse(String value) {
            if (StringUtils.isBlank(value)) {
                return EQ;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new NuxeoException("Unsupported dynamic field operator: " + value, e);
            }
        }
    }

    /**
     * Parses the {@code dynf_search} JSON payload.
     *
     * @param json a JSON array of criteria, may be blank
     * @return the parsed criteria, never {@code null}, possibly empty
     * @throws NuxeoException if the payload is not a valid JSON array of criteria
     */
    public static List<DynamicFieldsCriterion> parseAll(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new NuxeoException("Invalid dynf_search payload, expected a JSON array: " + json, e);
        }
        if (!root.isArray()) {
            throw new NuxeoException("Invalid dynf_search payload, expected a JSON array: " + json);
        }
        List<DynamicFieldsCriterion> criteria = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            criteria.add(parseOne(node));
        }
        return criteria;
    }

    protected static DynamicFieldsCriterion parseOne(JsonNode node) {
        if (!node.isObject()) {
            throw new NuxeoException("Invalid dynf_search entry, expected a JSON object: " + node);
        }
        String fieldName = node.path("fieldName").asText(null);
        String fieldTyp = node.path("fieldTyp").asText(null);
        String value = node.path("value").asText(null);
        if (StringUtils.isBlank(fieldName) || StringUtils.isBlank(fieldTyp) || StringUtils.isBlank(value)) {
            throw new NuxeoException(
                    "Incomplete dynf_search entry, fieldName/fieldTyp/value are all required: " + node);
        }
        var operator = Operator.parse(node.path("operator").asText(null));
        var fieldType = FieldType.parse(fieldTyp);
        if (operator == Operator.LIKE && fieldType != FieldType.STRING) {
            throw new NuxeoException("The 'like' operator is only supported on string fields, got: " + fieldTyp);
        }
        return new DynamicFieldsCriterion(fieldName, fieldType, operator, value);
    }
}
