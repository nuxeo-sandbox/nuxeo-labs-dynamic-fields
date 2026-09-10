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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.nuxeo.ecm.core.api.NuxeoException;

import nuxeo.labs.dynamic.fields.DynamicFieldsCriterion.FieldType;
import nuxeo.labs.dynamic.fields.DynamicFieldsCriterion.Operator;

/**
 * Tests the parsing of the {@code dynf_search} payload.
 *
 * @since 2025.3
 */
public class TestDynamicFieldsCriterion {

    @Test
    public void testParseSeveralCriteria() {
        var criteria = DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"color","fieldTyp":"string","value":"blue","operator":"eq"},\
                {"fieldName":"weight","fieldTyp":"double","value":"10.5","operator":"gte"}]""");

        assertEquals(2, criteria.size());
        assertEquals(new DynamicFieldsCriterion("color", FieldType.STRING, Operator.EQ, "blue"), criteria.get(0));
        assertEquals(new DynamicFieldsCriterion("weight", FieldType.DOUBLE, Operator.GTE, "10.5"), criteria.get(1));
    }

    @Test
    public void testOperatorDefaultsToEq() {
        var criteria = DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"status","fieldTyp":"string","value":"active"}]""");

        assertEquals(1, criteria.size());
        assertEquals(Operator.EQ, criteria.get(0).operator());
    }

    @Test
    public void testBlankAndEmptyPayloads() {
        assertTrue(DynamicFieldsCriterion.parseAll(null).isEmpty());
        assertTrue(DynamicFieldsCriterion.parseAll("").isEmpty());
        assertTrue(DynamicFieldsCriterion.parseAll("   ").isEmpty());
        assertTrue(DynamicFieldsCriterion.parseAll("[]").isEmpty());
    }

    /*
      The previous hand-rolled parser split on ',' and ':' and stripped every '"', so any value
      containing one of those characters was silently mangled. These are the regression tests.
    */
    @Test
    public void testValueContainingComma() {
        var criteria = DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"city","fieldTyp":"string","value":"Paris, France"}]""");

        assertEquals(1, criteria.size());
        assertEquals("Paris, France", criteria.get(0).value());
    }

    @Test
    public void testValueContainingColon() {
        var criteria = DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"ref","fieldTyp":"string","value":"urn:acme:1234"}]""");

        assertEquals("urn:acme:1234", criteria.get(0).value());
    }

    @Test
    public void testValueContainingEscapedQuote() {
        var criteria = DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"label","fieldTyp":"string","value":"say \\"hi\\""}]""");

        assertEquals("say \"hi\"", criteria.get(0).value());
    }

    @Test
    public void testValueContainingBraces() {
        var criteria = DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"tpl","fieldTyp":"string","value":"{a},{b}"}]""");

        assertEquals("{a},{b}", criteria.get(0).value());
    }

    @Test
    public void testMalformedJsonIsRejected() {
        assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("not json"));
        assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("{\"a\":1}"));
        assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("[{\"fieldName\":\"a\"}]"));
        assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("[\"plain string\"]"));
    }

    @Test
    public void testUnknownTypeAndOperatorAreRejected() {
        var unknownType = assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"a","fieldTyp":"geopoint","value":"x"}]"""));
        assertTrue(unknownType.getMessage().contains("geopoint"));

        assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"a","fieldTyp":"string","value":"x","operator":"matches"}]"""));
    }

    @Test
    public void testBlobIsRejectedWithAClearMessage() {
        var e = assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"attachment","fieldTyp":"blob","value":"x"}]"""));

        assertTrue(e.getMessage(), e.getMessage().contains("Blob dynamic fields cannot be searched"));
    }

    @Test
    public void testLikeIsRejectedOnNonStringFields() {
        var e = assertThrows(NuxeoException.class, () -> DynamicFieldsCriterion.parseAll("""
                [{"fieldName":"weight","fieldTyp":"double","value":"1","operator":"like"}]"""));

        assertTrue(e.getMessage(), e.getMessage().contains("only supported on string fields"));
    }

    @Test
    public void testColumnMapping() {
        assertEquals("dynf:values.stringValue", FieldType.STRING.column());
        assertEquals("dynf:values.integerValue", FieldType.INTEGER.column());
        assertEquals("dynf:values.doubleValue", FieldType.DOUBLE.column());
        assertEquals("dynf:values.booleanValue", FieldType.BOOLEAN.column());
        assertEquals("dynf:values.dateValue", FieldType.DATE.column());
    }
}
