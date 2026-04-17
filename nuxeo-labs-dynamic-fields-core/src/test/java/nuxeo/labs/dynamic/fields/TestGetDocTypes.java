/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy("nuxeo.labs.dynamic.fields.nuxeo-labs-dynamic-fields-core")
public class TestGetDocTypes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CUSTOMER_ID = "customer-001";

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automationService;

    @Test
    public void shouldReturnEmptyArrayWhenNoDefinitions() throws Exception {
        var result = callOperation(CUSTOMER_ID);
        var array = MAPPER.readTree(result.getString());
        assertTrue(array.isArray());
        assertEquals(0, array.size());
    }

    @Test
    public void shouldReturnSortedDistinctDocTypes() throws Exception {
        createCustomSchemaDef(CUSTOMER_ID, "Printer");
        createCustomSchemaDef(CUSTOMER_ID, "Chair");
        createCustomSchemaDef(CUSTOMER_ID, "Printer"); // duplicate
        createCustomSchemaDef(CUSTOMER_ID, "Desk");
        // Different customer — should not appear
        createCustomSchemaDef("customer-002", "Table");

        session.save();

        var result = callOperation(CUSTOMER_ID);
        var array = MAPPER.readTree(result.getString());

        assertTrue(array.isArray());
        assertEquals(3, array.size());
        // Sorted alphabetically
        assertEquals("Chair", array.get(0).asText());
        assertEquals("Desk", array.get(1).asText());
        assertEquals("Printer", array.get(2).asText());
    }

    private Blob callOperation(String customerId) throws Exception {
        var ctx = new OperationContext(session);
        Map<String, Object> params = new HashMap<>();
        params.put("customerId", customerId);
        return (Blob) automationService.run(ctx, GetDocTypes.ID, params);
    }

    private DocumentModel createCustomSchemaDef(String customerId, String defForTyp) {
        var doc = session.createDocumentModel("/", "def-" + customerId + "-" + defForTyp + "-" + System.nanoTime(),
                "CustomSchemaDef");
        doc.setPropertyValue("csd:customerId", customerId);
        doc.setPropertyValue("csd:defForTyp", defForTyp);
        return session.createDocument(doc);
    }
}
