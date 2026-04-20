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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.search.SearchIndex;
import org.nuxeo.ecm.core.search.SearchIndexingService;
import org.nuxeo.ecm.core.search.SearchQuery;
import org.nuxeo.ecm.core.search.SearchService;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchQueryTransformer;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchSearchClient;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateParserBase;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateDateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateDateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateTermParser;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.core.AggregateDateHistogram;
import org.nuxeo.ecm.platform.query.core.AggregateDateRange;
import org.nuxeo.ecm.platform.query.core.AggregateHistogram;
import org.nuxeo.ecm.platform.query.core.AggregateRange;
import org.nuxeo.ecm.platform.query.core.AggregateTerm;
import org.nuxeo.ecm.platform.query.nxql.SearchServicePageProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.opensearch1.OpenSearchClientService;
import org.nuxeo.runtime.opensearch1.client.OpenSearchClient;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.NestedQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.Aggregation;
import org.opensearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.opensearch.search.aggregations.bucket.filter.Filter;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;

/**
 * A page provider that searches documents using OpenSearch nested queries
 * on the {@code dynf:values} complex multivalued field.
 * <p>
 * This solves the NXQL limitation where wildcard queries on complex
 * multivalued fields are not correlated (each {@code *} is evaluated
 * independently). By using OpenSearch nested queries, we can ensure that
 * fieldName and value conditions match within the same entry.
 * <p>
 * Named parameters (JSON-encoded search criteria):
 * <ul>
 *   <li>{@code dynf_search} - JSON array of search criteria, each with:
 *       {@code fieldName}, {@code fieldTyp} (string|integer|double|boolean|date),
 *       {@code value}, and optionally {@code operator} (eq|lt|lte|gt|gte|like).
 *       Example: {@code [{"fieldName":"color","fieldTyp":"string","value":"blue","operator":"eq"}]}</li>
 * </ul>
 * <p>
 * The page provider definition should contain a base NXQL query (e.g. filtering
 * by document type, path, lifecycle state, etc.). The nested clauses for dynamic
 * fields are injected as a post-filter so they combine with the NXQL filter.
 *
 * @since 2025.1
 */
public class DynamicFieldsSearchPageProvider extends SearchServicePageProvider {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(DynamicFieldsSearchPageProvider.class);

    /** The nested path in the OpenSearch index. */
    private static final String NESTED_PATH = "dynf:values";

    @Override
    public List<DocumentModel> getCurrentPage() {

        // Use cache
        if (currentPageDocuments != null) {
            return currentPageDocuments;
        }

        DocumentModel searchDoc = getSearchDocumentModel();
        if (searchDoc == null) {
            return getEmptyResult();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> namedParameters = (Map<String, String>) searchDoc.getContextData(NAMED_PARAMETERS);
        if (namedParameters == null) {
            return super.getCurrentPage();
        }

        String dynfSearch = namedParameters.get("dynf_search");
        if (StringUtils.isBlank(dynfSearch)) {
            // No dynamic field criteria — fall back to standard NXQL search
            return super.getCurrentPage();
        }

        // Parse the search criteria JSON
        List<SearchCriterion> criteria = parseSearchCriteria(dynfSearch);
        if (criteria.isEmpty()) {
            return super.getCurrentPage();
        }

        error = null;
        errorMessage = null;

        currentPageDocuments = new ArrayList<>();
        CoreSession coreSession = getCoreSession();
        if (query == null) {
            buildQuery(coreSession);
        }
        if (query == null) {
            throw new NuxeoException("Cannot perform null query: check provider '%s'".formatted(getName()));
        }

        // Build nested queries for each dynamic field criterion
        BoolQueryBuilder nestedClauses = QueryBuilders.boolQuery();
        for (var criterion : criteria) {
            NestedQueryBuilder nestedQuery = buildNestedQuery(criterion);
            nestedClauses.must(nestedQuery);
        }

        // Resolve search index
        SearchService searchService = Framework.getService(SearchService.class);
        SearchIndexingService indexingService = Framework.getService(SearchIndexingService.class);
        String repository = coreSession.getRepositoryName();
        String defaultIndexName = searchService.getDefaultIndexName(repository);
        SearchIndex searchIndex = searchService.getSearchIndex(defaultIndexName);

        NuxeoPrincipal principal = coreSession.getPrincipal();

        // Build aggregates from the page provider definition
        List<Aggregate<? extends Bucket>> aggregates = buildAggregates();

        SearchQuery searchQuery = SearchQuery.builder(query, principal)
                .searchIndex(List.of(searchIndex))
                .offset((int) getCurrentPageOffset())
                .limit((int) getPageSize())
                .addAggregates(aggregates)
                .build();

        // Get the OpenSearchSearchClient
        var searchClient = indexingService.getClient(searchIndex.client());
        if (!(searchClient instanceof OpenSearchSearchClient osSearchClient)) {
            throw new NuxeoException(
                    "Dynamic fields search requires an OpenSearch search client. Got: "
                            + (searchClient != null ? searchClient.getClass().getName() : "null"));
        }
        Map<String, String> technicalIndexes = osSearchClient.getTechnicalIndexes();

        // Convert NXQL to OpenSearch query
        OpenSearchQueryTransformer transformer = new OpenSearchQueryTransformer(technicalIndexes, Map.of());
        SearchRequest osRequest = transformer.apply(searchQuery);
        SearchSourceBuilder source = osRequest.source();

        // Combine NXQL query with nested clauses using a bool query
        QueryBuilder nxqlQuery = source.query();
        QueryBuilder existingPostFilter = source.postFilter();

        BoolQueryBuilder combinedQuery = QueryBuilders.boolQuery();
        if (nxqlQuery != null) {
            combinedQuery.must(nxqlQuery);
        }
        combinedQuery.must(nestedClauses);

        source.query(combinedQuery);

        if (existingPostFilter != null) {
            source.postFilter(existingPostFilter);
        }

        // Execute via raw OpenSearch client
        OpenSearchClientService osClientService = Framework.getService(OpenSearchClientService.class);
        if (osClientService == null) {
            throw new NuxeoException(
                    "OpenSearchClientService is not available. Dynamic fields search requires an OpenSearch backend.");
        }

        String lowLevelClientId = namedParameters.getOrDefault("opensearch_client_id", "search/default");
        OpenSearchClient client = osClientService.getClient(lowLevelClientId);
        if (client == null) {
            throw new NuxeoException(
                    "OpenSearch client '%s' is not available. Check your OpenSearch configuration.".formatted(
                            lowLevelClientId));
        }

        SearchResponse response = client.search(osRequest);
        SearchHits hits = response.getHits();

        // Load documents from repository
        List<DocumentModel> result = new ArrayList<>();
        for (SearchHit hit : hits.getHits()) {
            var docRef = new IdRef(hit.getId());
            if (coreSession.exists(docRef)) {
                result.add(coreSession.getDocument(docRef));
            }
        }

        currentPageDocuments = result;

        // Parse aggregate results
        if (response.getAggregations() != null && !aggregates.isEmpty()) {
            currentAggregates = new HashMap<>();
            for (Aggregate<? extends Bucket> agg : aggregates) {
                parseAggregate(agg, response);
                currentAggregates.put(agg.getId(), agg);
            }
        }

        setResultsCount(hits.getTotalHits().value);

        return result;
    }

    /**
     * Builds a nested query for a single search criterion.
     * The nested query ensures fieldName and value match within the same
     * entry of the {@code dynf:values} array.
     */
    protected NestedQueryBuilder buildNestedQuery(SearchCriterion criterion) {
        BoolQueryBuilder innerBool = QueryBuilders.boolQuery();

        // Always match on fieldName
        innerBool.must(QueryBuilders.termQuery(NESTED_PATH + ".fieldName", criterion.fieldName));

        // Determine the value field based on field type
        String valueField = switch (criterion.fieldTyp) {
            case "string" -> NESTED_PATH + ".stringValue";
            case "integer" -> NESTED_PATH + ".integerValue";
            case "double" -> NESTED_PATH + ".doubleValue";
            case "boolean" -> NESTED_PATH + ".booleanValue";
            case "date" -> NESTED_PATH + ".dateValue";
            default -> throw new NuxeoException("Unsupported field type: " + criterion.fieldTyp);
        };

        // Build value condition based on operator
        String operator = criterion.operator != null ? criterion.operator : "eq";
        QueryBuilder valueQuery = switch (operator) {
            case "eq" -> QueryBuilders.termQuery(valueField, criterion.value);
            case "like" -> {
                if ("string".equals(criterion.fieldTyp)) {
                    yield QueryBuilders.matchQuery(valueField + ".fulltext", criterion.value);
                } else {
                    throw new NuxeoException("'like' operator is only supported for string fields");
                }
            }
            case "lt" -> QueryBuilders.rangeQuery(valueField).lt(criterion.value);
            case "lte" -> QueryBuilders.rangeQuery(valueField).lte(criterion.value);
            case "gt" -> QueryBuilders.rangeQuery(valueField).gt(criterion.value);
            case "gte" -> QueryBuilders.rangeQuery(valueField).gte(criterion.value);
            default -> throw new NuxeoException("Unsupported operator: " + operator);
        };

        innerBool.must(valueQuery);

        return QueryBuilders.nestedQuery(NESTED_PATH, innerBool, org.apache.lucene.search.join.ScoreMode.None);
    }

    /**
     * Parses the JSON search criteria string into a list of {@link SearchCriterion}.
     * Expected format: {@code [{"fieldName":"X","fieldTyp":"string","value":"Y","operator":"eq"}, ...]}
     */
    protected List<SearchCriterion> parseSearchCriteria(String json) {
        List<SearchCriterion> criteria = new ArrayList<>();
        try {
            // Simple JSON array parsing without Jackson dependency
            // Remove outer brackets and split by },{ pattern
            var trimmed = json.trim();
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                log.error("Invalid dynf_search format, expected JSON array: {}", json);
                return criteria;
            }
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            if (trimmed.isEmpty()) {
                return criteria;
            }

            // Split on },{ while handling nested braces
            List<String> objects = splitJsonObjects(trimmed);
            for (String obj : objects) {
                var criterion = parseOneCriterion(obj.trim());
                if (criterion != null) {
                    criteria.add(criterion);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse dynf_search criteria: {}", json, e);
        }
        return criteria;
    }

    /**
     * Splits a string containing multiple JSON objects separated by commas.
     */
    protected List<String> splitJsonObjects(String input) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(input.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < input.length()) {
            result.add(input.substring(start).trim());
        }
        return result;
    }

    /**
     * Parses a single JSON object string into a SearchCriterion.
     */
    protected SearchCriterion parseOneCriterion(String jsonObj) {
        // Remove outer braces
        if (jsonObj.startsWith("{")) {
            jsonObj = jsonObj.substring(1);
        }
        if (jsonObj.endsWith("}")) {
            jsonObj = jsonObj.substring(0, jsonObj.length() - 1);
        }

        Map<String, String> map = new HashMap<>();
        // Simple key:value parsing for flat JSON objects with string values
        var parts = jsonObj.split(",");
        for (String part : parts) {
            int colonIdx = part.indexOf(':');
            if (colonIdx < 0) {
                continue;
            }
            String key = part.substring(0, colonIdx).trim().replace("\"", "");
            String value = part.substring(colonIdx + 1).trim().replace("\"", "");
            map.put(key, value);
        }

        String fieldName = map.get("fieldName");
        String fieldTyp = map.get("fieldTyp");
        String value = map.get("value");
        if (StringUtils.isBlank(fieldName) || StringUtils.isBlank(fieldTyp) || StringUtils.isBlank(value)) {
            log.warn("Incomplete search criterion: fieldName={}, fieldTyp={}, value={}", fieldName, fieldTyp, value);
            return null;
        }

        var criterion = new SearchCriterion();
        criterion.fieldName = fieldName;
        criterion.fieldTyp = fieldTyp;
        criterion.value = value;
        criterion.operator = map.getOrDefault("operator", "eq");
        return criterion;
    }

    /**
     * Parses an aggregate result from the OpenSearch response.
     */
    @SuppressWarnings("unchecked")
    protected void parseAggregate(Aggregate<? extends Bucket> agg, SearchResponse response) {
        String filterId = AggregateParserBase.getFilterId(agg);
        Filter filter = response.getAggregations().get(filterId);
        if (filter == null) {
            log.debug("No filter aggregation found for aggregate '{}' (filterId='{}')", agg.getId(), filterId);
            return;
        }
        Aggregation aggregation = filter.getAggregations().get(agg.getId());
        if (aggregation == null) {
            log.debug("No aggregation found for aggregate '{}'", agg.getId());
            return;
        }

        if (agg instanceof AggregateTerm a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateTermParser.parseBuckets(mba.getBuckets()));
        } else if (agg instanceof AggregateRange a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateRangeParser.parseBuckets(mba.getBuckets()));
        } else if (agg instanceof AggregateDateRange a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateDateRangeParser.parseBuckets(mba.getBuckets()));
        } else if (agg instanceof AggregateHistogram a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateHistogramParser.parseBuckets(mba.getBuckets(), a));
        } else if (agg instanceof AggregateDateHistogram a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateDateHistogramParser.parseBuckets(mba.getBuckets(), a));
        } else {
            log.warn("Unsupported aggregate type for parsing: {} ({})", agg.getId(), agg.getClass().getSimpleName());
        }
    }

    protected DocumentModelList getEmptyResult() {
        setResultsCount(0);
        return new DocumentModelListImpl();
    }

    /**
     * A single search criterion for a dynamic field.
     */
    protected static class SearchCriterion {
        String fieldName;
        String fieldTyp;
        String value;
        String operator; // eq, lt, lte, gt, gte, like
    }
}
