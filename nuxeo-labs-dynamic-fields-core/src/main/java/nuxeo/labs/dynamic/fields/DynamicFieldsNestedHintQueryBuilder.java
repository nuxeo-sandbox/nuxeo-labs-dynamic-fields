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

import java.util.List;

import org.apache.lucene.search.join.ScoreMode;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.query.sql.model.EsHint;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchHintQueryBuilder;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.Operator;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;

/**
 * Builds correlated OpenSearch {@code nested} queries on {@code dynf:values} from a JSON array of criteria.
 * <p>
 * Registered as the {@code dynamicFieldsNested} NXQL hint operator, so it can be used from any page provider or raw
 * NXQL:
 *
 * <pre>
 * SELECT * FROM Document
 *  WHERE /&#42;+ES: OPERATOR(dynamicFieldsNested) &#42;/ dynf:values = '[{"fieldName":"color", ...}]'
 * </pre>
 *
 * Each criterion becomes its own {@code nested} clause, which is what guarantees that {@code fieldName} and the value
 * match within the <em>same</em> entry of the array — the correlation that plain NXQL cannot express.
 *
 * @since 2025.3
 */
public class DynamicFieldsNestedHintQueryBuilder implements OpenSearchHintQueryBuilder {

    /** The NXQL hint operator name, as registered in {@code dynamic-fields-hints.xml}. */
    public static final String OPERATOR = "dynamicFieldsNested";

    @Override
    public QueryBuilder make(EsHint hint, String fieldName, Object value) {
        if (value == null) {
            throw new NuxeoException("The " + OPERATOR + " hint requires a JSON array of criteria");
        }
        return build(DynamicFieldsCriterion.parseAll(value.toString()));
    }

    /**
     * Combines one {@code nested} clause per criterion with {@code must}.
     */
    public QueryBuilder build(List<DynamicFieldsCriterion> criteria) {
        if (criteria.isEmpty()) {
            return QueryBuilders.matchAllQuery();
        }
        BoolQueryBuilder combined = QueryBuilders.boolQuery();
        criteria.forEach(criterion -> combined.must(buildNestedQuery(criterion)));
        return combined;
    }

    /**
     * Builds the {@code nested} query for a single criterion.
     */
    public QueryBuilder buildNestedQuery(DynamicFieldsCriterion criterion) {
        var innerBool = QueryBuilders.boolQuery()
                                     .must(QueryBuilders.termQuery(DynamicFieldsCriterion.NESTED_PATH + ".fieldName",
                                             criterion.fieldName()))
                                     .must(buildValueQuery(criterion));
        return QueryBuilders.nestedQuery(DynamicFieldsCriterion.NESTED_PATH, innerBool, ScoreMode.None);
    }

    protected QueryBuilder buildValueQuery(DynamicFieldsCriterion criterion) {
        String field = criterion.fieldType().column();
        String value = criterion.value();
        return switch (criterion.operator()) {
            case EQ -> buildEqualsQuery(criterion, field, value);
            /*
              Matched on the analyzed sub-field, with AND so that every term must be present.
              With the default OR, "blue sky" would also match a value containing only "sky".
            */
            case LIKE -> QueryBuilders.matchQuery(field + ".fulltext", value).operator(Operator.AND);
            case LT -> QueryBuilders.rangeQuery(field).lt(value);
            case LTE -> QueryBuilders.rangeQuery(field).lte(value);
            case GT -> QueryBuilders.rangeQuery(field).gt(value);
            case GTE -> QueryBuilders.rangeQuery(field).gte(value);
        };
    }

    /**
     * Equality, with a day-wide range for dates.
     * <p>
     * Nuxeo stores every date as a full timestamp, and the date picker submits midnight UTC, so a plain term query
     * would only ever match values recorded exactly at midnight.
     */
    protected QueryBuilder buildEqualsQuery(DynamicFieldsCriterion criterion, String field, String value) {
        if (criterion.fieldType() == DynamicFieldsCriterion.FieldType.DATE) {
            return QueryBuilders.rangeQuery(field).gte(value).lt(value + "||+1d");
        }
        return QueryBuilders.termQuery(field, value);
    }
}
