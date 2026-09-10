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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.TypeConstants;
import org.nuxeo.ecm.core.schema.types.ComplexType;
import org.nuxeo.ecm.core.schema.types.ListType;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * Tests the {@code dynamic-fields} schema, in particular that {@code blobValue} really is a Nuxeo blob.
 * <p>
 * Nuxeo recognises a blob by the complex type being named exactly {@code content} (see
 * {@link TypeConstants#isContentType}), so a hand-rolled equivalent would silently degrade to a plain complex
 * property with no binary storage and no download URL.
 *
 * @since 2025.3
 */
@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy("nuxeo.labs.dynamic.fields.nuxeo-labs-dynamic-fields-core")
public class TestDynamicFieldsSchemas {

    @Inject
    protected CoreSession session;

    @Inject
    protected SchemaManager schemaManager;

    protected ComplexType fieldValueType() {
        var schema = schemaManager.getSchema("dynamic-fields");
        assertNotNull("the dynamic-fields schema should be registered", schema);
        var values = schema.getField("values");
        assertNotNull("dynf:values should be declared", values);
        assertTrue("dynf:values should be a list", values.getType().isListType());
        return (ComplexType) ((ListType) values.getType()).getFieldType();
    }

    @Test
    public void testBlobValueIsARealBlob() {
        var blobValue = fieldValueType().getField("blobValue");

        assertNotNull("blobValue should be declared", blobValue);
        assertEquals("content", blobValue.getType().getName());
        assertTrue("blobValue must be recognised as a Nuxeo blob",
                TypeConstants.isContentType(blobValue.getType()));
    }

    @Test
    public void testScalarColumnsAreDeclared() {
        var type = fieldValueType();
        for (String field : List.of("fieldName", "stringValue", "integerValue", "doubleValue", "booleanValue",
                "dateValue")) {
            assertNotNull(field + " should be declared", type.getField(field));
        }
    }

    @Test
    public void testBlobRoundTrip() throws IOException {
        DocumentModel doc = session.createDocumentModel("/", "withBlob", "File");
        doc.addFacet("DynamicFields");
        Blob blob = Blobs.createBlob("dynamic content", "text/plain", null, "dynamic.txt");
        doc.setPropertyValue("dynf:values",
                (java.io.Serializable) List.of(Map.of("fieldName", "attachment", "blobValue", blob)));
        doc = session.createDocument(doc);
        session.save();

        Blob stored = (Blob) doc.getPropertyValue("dynf:values/0/blobValue");
        assertNotNull("the blob should be readable back as a Blob", stored);
        assertEquals("dynamic.txt", stored.getFilename());
        assertEquals("text/plain", stored.getMimeType());
        assertEquals("dynamic content", stored.getString());
    }

    @Test
    public void testCustomSchemaDefSchema() {
        var schema = schemaManager.getSchema("custom-schema-def");
        assertNotNull(schema);
        assertNotNull(schema.getField("customerId"));
        assertNotNull(schema.getField("defForTyp"));

        var definition = schema.getField("definition");
        assertTrue(definition.getType().isListType());
        var item = (ComplexType) ((ListType) definition.getType()).getFieldType();
        assertNotNull(item.getField("fieldName"));
        assertNotNull(item.getField("fieldTyp"));
    }
}
