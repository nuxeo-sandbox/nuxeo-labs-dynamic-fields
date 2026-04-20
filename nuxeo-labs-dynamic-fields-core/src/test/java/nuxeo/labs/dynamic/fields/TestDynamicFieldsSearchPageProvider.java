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

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.CoreSearchFeature;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.ecm.platform.query.nxql.SearchServicePageProvider;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Tests for the {@link DynamicFieldsSearchPageProvider}.
 * <p>
 * These tests verify page provider registration, JSON parsing, and fallback
 * behavior. The actual nested OpenSearch query path requires a running
 * OpenSearch instance with the dynamic-fields-opensearch template active
 * and must be tested with {@code -Dnuxeo.test.search=opensearch1}.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(CoreSearchFeature.class)
@Deploy("org.nuxeo.ecm.platform.query.api")
@Deploy("org.nuxeo.ecm.automation.core")
@Deploy("nuxeo.labs.dynamic.fields.nuxeo-labs-dynamic-fields-core")
public class TestDynamicFieldsSearchPageProvider {

    @Inject
    protected CoreSession session;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    @Test
    public void testPageProviderIsRegistered() {
        var ppdef = pageProviderService.getPageProviderDefinition("dynf_search");
        assertNotNull("dynf_search page provider should be registered", ppdef);
    }

    @Test
    public void testFallbackWithoutDynfSearch() {
        // Create a document with the DynamicFields facet
        DocumentModel doc = session.createDocumentModel("/", "testDoc", "File");
        doc.addFacet("DynamicFields");
        doc.setPropertyValue("dc:title", "Test Dynamic Fields");
        doc.setPropertyValue("dynf:customerId", "ABCD-1234");
        doc = session.createDocument(doc);

        txFeature.nextTransaction();

        // Query without dynf_search parameter — should fall back to standard NXQL
        var ppdef = pageProviderService.getPageProviderDefinition("dynf_search");
        assertNotNull("dynf_search definition should exist", ppdef);

        HashMap<String, Serializable> props = new HashMap<>();
        props.put(SearchServicePageProvider.CORE_SESSION_PROPERTY, (Serializable) session);
        @SuppressWarnings("unchecked")
        PageProvider<DocumentModel> pp = (PageProvider<DocumentModel>) pageProviderService.getPageProvider(
                "dynf_search", ppdef, null, null, 20L, 0L, props);
        assertNotNull("Page provider should not be null", pp);

        List<DocumentModel> results = pp.getCurrentPage();
        assertNotNull(results);
        // With the repository search backend, facet queries may not return results.
        // The important thing is that the fallback path executes without error.
    }

    @Test
    public void testParseSearchCriteria() {
        var provider = new DynamicFieldsSearchPageProvider();

        // Test valid JSON
        var criteria = provider.parseSearchCriteria(
                """
                [{"fieldName":"color","fieldTyp":"string","value":"blue","operator":"eq"},\
                {"fieldName":"weight","fieldTyp":"double","value":"10.5","operator":"gte"}]""");
        assertEquals(2, criteria.size());

        var first = criteria.get(0);
        assertEquals("color", first.fieldName);
        assertEquals("string", first.fieldTyp);
        assertEquals("blue", first.value);
        assertEquals("eq", first.operator);

        var second = criteria.get(1);
        assertEquals("weight", second.fieldName);
        assertEquals("double", second.fieldTyp);
        assertEquals("10.5", second.value);
        assertEquals("gte", second.operator);

        // Test empty array
        criteria = provider.parseSearchCriteria("[]");
        assertEquals(0, criteria.size());

        // Test invalid JSON
        criteria = provider.parseSearchCriteria("not json");
        assertEquals(0, criteria.size());

        // Test single criterion without operator (should default to eq)
        criteria = provider.parseSearchCriteria(
                """
                [{"fieldName":"status","fieldTyp":"string","value":"active"}]""");
        assertEquals(1, criteria.size());
        assertEquals("eq", criteria.get(0).operator);
    }
}
