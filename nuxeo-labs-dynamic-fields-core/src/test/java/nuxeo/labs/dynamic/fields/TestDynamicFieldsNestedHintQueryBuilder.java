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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.NestedQueryBuilder;

import nuxeo.labs.dynamic.fields.DynamicFieldsCriterion.FieldType;
import nuxeo.labs.dynamic.fields.DynamicFieldsCriterion.Operator;

/**
 * Tests the OpenSearch query generated for dynamic field criteria. No running OpenSearch is required: the assertions
 * are made on the serialised query.
 *
 * @since 2025.3
 */
public class TestDynamicFieldsNestedHintQueryBuilder {

    protected final DynamicFieldsNestedHintQueryBuilder builder = new DynamicFieldsNestedHintQueryBuilder();

    protected String queryFor(String fieldName, FieldType type, Operator operator, String value) {
        return builder.buildNestedQuery(new DynamicFieldsCriterion(fieldName, type, operator, value)).toString();
    }

    @Test
    public void testCorrelationIsExpressedAsANestedQuery() {
        var query = builder.buildNestedQuery(
                new DynamicFieldsCriterion("color", FieldType.STRING, Operator.EQ, "blue"));

        assertTrue(query instanceof NestedQueryBuilder);

        String json = query.toString();
        assertTrue(json, json.contains("\"path\" : \"dynf:values\""));
        assertTrue(json, json.contains("\"dynf:values.fieldName\""));
        assertTrue(json, json.contains("\"dynf:values.stringValue\""));
        assertTrue(json, json.contains("\"blue\""));
    }

    @Test
    public void testOneNestedClausePerCriterion() {
        var query = builder.build(java.util.List.of( //
                new DynamicFieldsCriterion("color", FieldType.STRING, Operator.EQ, "blue"),
                new DynamicFieldsCriterion("weight", FieldType.DOUBLE, Operator.GTE, "10.5")));

        assertTrue(query instanceof BoolQueryBuilder);
        assertEquals(2, ((BoolQueryBuilder) query).must().size());
    }

    @Test
    public void testEmptyCriteriaMatchAll() {
        assertEquals("match_all", builder.build(java.util.List.of()).getName());
    }

    /*
      A term query on a date only matches an exact instant. Nuxeo stores dates as full timestamps and
      nuxeo-date-picker submits midnight UTC, so equality has to be a day-wide range instead.
    */
    @Test
    public void testDateEqualityUsesADayRange() {
        String json = queryFor("when", FieldType.DATE, Operator.EQ, "2026-01-15T00:00:00.000Z");

        assertTrue(json, json.contains("\"range\""));
        assertTrue(json, json.contains("\"from\" : \"2026-01-15T00:00:00.000Z\""));
        assertTrue(json, json.contains("\"to\" : \"2026-01-15T00:00:00.000Z||+1d\""));
        assertTrue(json, json.contains("\"include_lower\" : true"));
        assertTrue(json, json.contains("\"include_upper\" : false"));
    }

    @Test
    public void testNonDateEqualityUsesATermQuery() {
        assertTrue(queryFor("color", FieldType.STRING, Operator.EQ, "blue").contains("\"term\""));
        assertTrue(queryFor("flag", FieldType.BOOLEAN, Operator.EQ, "true").contains("\"term\""));
        assertTrue(queryFor("count", FieldType.INTEGER, Operator.EQ, "3").contains("\"term\""));
    }

    /*
      With the default OR operator, "blue sky" would also match a value containing only "sky".
    */
    @Test
    public void testLikeMatchesOnFulltextWithAndOperator() {
        String json = queryFor("label", FieldType.STRING, Operator.LIKE, "blue sky");

        assertTrue(json, json.contains("dynf:values.stringValue.fulltext"));
        assertTrue(json, json.contains("\"operator\" : \"AND\""));
    }

    /*
      OpenSearch serialises a range as from/to plus include_lower/include_upper,
      so lt/lte and gt/gte only differ by the inclusive flag.
    */
    @Test
    public void testRangeOperators() {
        String lt = queryFor("n", FieldType.INTEGER, Operator.LT, "5");
        assertTrue(lt, lt.contains("\"to\" : \"5\"") && lt.contains("\"include_upper\" : false"));

        String lte = queryFor("n", FieldType.INTEGER, Operator.LTE, "5");
        assertTrue(lte, lte.contains("\"to\" : \"5\"") && lte.contains("\"include_upper\" : true"));

        String gt = queryFor("n", FieldType.INTEGER, Operator.GT, "5");
        assertTrue(gt, gt.contains("\"from\" : \"5\"") && gt.contains("\"include_lower\" : false"));

        String gte = queryFor("n", FieldType.INTEGER, Operator.GTE, "5");
        assertTrue(gte, gte.contains("\"from\" : \"5\"") && gte.contains("\"include_lower\" : true"));
    }

    @Test
    public void testHintEntryPointParsesTheJsonPayload() {
        var query = builder.make(null, "dynf:values", """
                [{"fieldName":"color","fieldTyp":"string","value":"blue"}]""");

        assertTrue(query instanceof BoolQueryBuilder);
        assertTrue(query.toString().contains("\"blue\""));
    }
}
