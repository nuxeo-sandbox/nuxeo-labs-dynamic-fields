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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.impl.SimpleDocumentModel;
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
 * The provider only rewrites the NXQL: the actual nested query is produced by
 * {@link DynamicFieldsNestedHintQueryBuilder} (covered by its own test) and executed by the platform. These tests
 * therefore assert on the generated NXQL, which needs no running OpenSearch.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(CoreSearchFeature.class)
@Deploy("org.nuxeo.ecm.platform.query.api")
@Deploy("org.nuxeo.ecm.automation.core")
@Deploy("nuxeo.labs.dynamic.fields.nuxeo-labs-dynamic-fields-core")
public class TestDynamicFieldsSearchPageProvider {

    protected static final String PP_NAME = "dynf_search";

    protected static final String CRITERIA = """
            [{"fieldName":"color","fieldTyp":"string","value":"blue"}]""";

    @Inject
    protected CoreSession session;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    protected DynamicFieldsSearchPageProvider newProvider(String dynfSearch) {
        var ppdef = pageProviderService.getPageProviderDefinition(PP_NAME);
        assertNotNull("dynf_search page provider should be registered", ppdef);

        DocumentModel searchDocument = SimpleDocumentModel.empty();
        if (dynfSearch != null) {
            searchDocument.putContextData(PageProviderService.NAMED_PARAMETERS,
                    (Serializable) Map.of(DynamicFieldsSearchPageProvider.DYNF_SEARCH_PARAM, dynfSearch));
        }

        HashMap<String, Serializable> props = new HashMap<>();
        props.put(SearchServicePageProvider.CORE_SESSION_PROPERTY, (Serializable) session);
        @SuppressWarnings("unchecked")
        var pp = (PageProvider<DocumentModel>) pageProviderService.getPageProvider(PP_NAME, ppdef, searchDocument, null,
                20L, 0L, props);
        return (DynamicFieldsSearchPageProvider) pp;
    }

    @Test
    public void testPageProviderIsRegistered() {
        assertNotNull(pageProviderService.getPageProviderDefinition(PP_NAME));
    }

    @Test
    public void testHintIsInjectedWhenCriteriaAreProvided() {
        var provider = newProvider(CRITERIA);
        provider.buildQuery(session);

        String query = provider.getCurrentQuery();
        assertTrue(query, query.contains("OPERATOR(dynamicFieldsNested)"));
        assertTrue(query, query.contains("dynf:values ="));
        // the fixed part must still be there
        assertTrue(query, query.contains("ecm:mixinType = 'DynamicFields'"));
    }

    @Test
    public void testNoHintWithoutCriteria() {
        var provider = newProvider(null);
        provider.buildQuery(session);

        assertFalse(provider.getCurrentQuery(), provider.getCurrentQuery().contains("dynamicFieldsNested"));
    }

    @Test
    public void testNoHintForBlankOrEmptyCriteria() {
        for (String blank : List.of("", "   ", "[]")) {
            var provider = newProvider(blank);
            provider.buildQuery(session);
            assertFalse(provider.getCurrentQuery(), provider.getCurrentQuery().contains("dynamicFieldsNested"));
        }
    }

    @Test
    public void testCriteriaAreEscapedIntoTheNxql() {
        var provider = newProvider("""
                [{"fieldName":"city","fieldTyp":"string","value":"O'Hara"}]""");
        provider.buildQuery(session);

        // the single quote must be backslash-escaped so the NXQL literal stays well formed
        assertTrue(provider.getCurrentQuery(), provider.getCurrentQuery().contains("O\\'Hara"));
    }

    @Test
    public void testFallbackQueryStillRuns() {
        DocumentModel doc = session.createDocumentModel("/", "testDoc", "File");
        doc.addFacet("DynamicFields");
        doc.setPropertyValue("dc:title", "Test Dynamic Fields");
        doc = session.createDocument(doc);
        txFeature.nextTransaction();

        var provider = newProvider(null);
        assertNotNull(provider.getCurrentPage());
    }

    /* ==================== NXQL rewriting ==================== */

    @Test
    public void testClauseIsInsertedBeforeOrderBy() {
        String result = DynamicFieldsSearchPageProvider.appendBeforeOrderBy(
                "SELECT * FROM Document WHERE a = 1 ORDER BY dc:title ASC", "HINT");

        assertEquals("SELECT * FROM Document WHERE a = 1 AND HINT ORDER BY dc:title ASC", result);
    }

    @Test
    public void testClauseIsAppendedWhenThereIsNoOrderBy() {
        String result = DynamicFieldsSearchPageProvider.appendBeforeOrderBy("SELECT * FROM Document WHERE a = 1",
                "HINT");

        assertEquals("SELECT * FROM Document WHERE a = 1 AND HINT", result);
    }

    /*
      A predicate value containing "ORDER BY" must not be mistaken for the sort clause.
    */
    @Test
    public void testOrderByInsideAStringLiteralIsIgnored() {
        String result = DynamicFieldsSearchPageProvider.appendBeforeOrderBy(
                "SELECT * FROM Document WHERE dc:title = 'an ORDER BY trap'", "HINT");

        assertEquals("SELECT * FROM Document WHERE dc:title = 'an ORDER BY trap' AND HINT", result);
    }

    @Test
    public void testOrderByAfterAStringLiteralIsStillFound() {
        String result = DynamicFieldsSearchPageProvider.appendBeforeOrderBy(
                "SELECT * FROM Document WHERE dc:title = 'an ORDER BY trap' ORDER BY dc:created", "HINT");

        assertEquals("SELECT * FROM Document WHERE dc:title = 'an ORDER BY trap' AND HINT ORDER BY dc:created", result);
    }

    @Test
    public void testEscapedQuoteInsideALiteralIsHandled() {
        String result = DynamicFieldsSearchPageProvider.appendBeforeOrderBy(
                "SELECT * FROM Document WHERE dc:title = 'O\\'Hara ORDER BY x' ORDER BY dc:created", "HINT");

        assertEquals("SELECT * FROM Document WHERE dc:title = 'O\\'Hara ORDER BY x' AND HINT ORDER BY dc:created",
                result);
    }
}
