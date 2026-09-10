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

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;

import java.io.Serial;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.platform.query.nxql.SearchServicePageProvider;

/**
 * A page provider that searches documents by dynamic field values, using correlated OpenSearch {@code nested} queries
 * on {@code dynf:values}.
 * <p>
 * This solves the NXQL limitation where wildcard queries on complex multivalued fields are not correlated (each
 * {@code *} is evaluated independently), so {@code dynf:values/*&#47;fieldName = 'color'} and
 * {@code dynf:values/*&#47;stringValue = 'blue'} may match two different entries.
 * <p>
 * The provider only overrides {@link #buildQuery(CoreSession)}: it appends a
 * {@value DynamicFieldsNestedHintQueryBuilder#OPERATOR} NXQL hint carrying the criteria, then lets
 * {@link SearchServicePageProvider} run the search unchanged. Security filtering, aggregates, highlights, sorting,
 * document loading, search events and result limits therefore all behave exactly as with any standard page provider.
 * <p>
 * Named parameters:
 * <ul>
 * <li>{@code dynf_search} — JSON array of criteria, each with {@code fieldName}, {@code fieldTyp}
 * (string|integer|double|boolean|date), {@code value} and optionally {@code operator}
 * (eq|lt|lte|gt|gte|like). Example:
 * {@code [{"fieldName":"color","fieldTyp":"string","value":"blue","operator":"eq"}]}</li>
 * </ul>
 * <p>
 * Requires the {@code dynamic-fields-opensearch} template, which maps {@code dynf:values} as {@code nested}.
 *
 * @since 2025.1
 */
public class DynamicFieldsSearchPageProvider extends SearchServicePageProvider {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(DynamicFieldsSearchPageProvider.class);

    /** The named parameter holding the JSON search criteria. */
    public static final String DYNF_SEARCH_PARAM = "dynf_search";

    protected static final String ORDER_BY = " ORDER BY ";

    @Override
    protected void buildQuery(CoreSession coreSession) {
        super.buildQuery(coreSession);

        String criteria = getSearchCriteria();
        if (StringUtils.isBlank(criteria) || query == null) {
            return;
        }
        // Fail fast on a malformed payload rather than at OpenSearch call time
        if (DynamicFieldsCriterion.parseAll(criteria).isEmpty()) {
            return;
        }

        String clause = "/*+ES: OPERATOR(%s) */ %s = %s".formatted(DynamicFieldsNestedHintQueryBuilder.OPERATOR,
                DynamicFieldsCriterion.NESTED_PATH, NXQL.escapeString(criteria));
        query = appendBeforeOrderBy(query, clause);
        log.debug("Dynamic fields query for provider '{}': {}", this::getName, () -> query);
    }

    /**
     * Returns the raw {@code dynf_search} named parameter, or {@code null} when absent.
     */
    protected String getSearchCriteria() {
        var searchDocument = getSearchDocumentModel();
        if (searchDocument == null) {
            return null;
        }
        if (searchDocument.getContextData(NAMED_PARAMETERS) instanceof Map<?, ?> namedParameters) {
            Object value = namedParameters.get(DYNF_SEARCH_PARAM);
            return value == null ? null : value.toString();
        }
        return null;
    }

    /**
     * Inserts {@code AND <clause>} before the trailing {@code ORDER BY}, if any.
     * <p>
     * Quoted string literals are skipped so that an {@code ORDER BY} occurring inside a predicate value is not mistaken
     * for the sort clause.
     */
    protected static String appendBeforeOrderBy(String nxql, String clause) {
        int orderByIndex = indexOfOrderBy(nxql);
        if (orderByIndex < 0) {
            return nxql + " AND " + clause;
        }
        return nxql.substring(0, orderByIndex) + " AND " + clause + nxql.substring(orderByIndex);
    }

    protected static int indexOfOrderBy(String nxql) {
        boolean inLiteral = false;
        for (int i = 0; i < nxql.length(); i++) {
            char c = nxql.charAt(i);
            if (inLiteral) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inLiteral = false;
                }
            } else if (c == '\'') {
                inLiteral = true;
            } else if (c == ' ' && nxql.regionMatches(true, i, ORDER_BY, 0, ORDER_BY.length())) {
                return i;
            }
        }
        return -1;
    }
}
