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
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.query.sql.NXQL;

/**
 * Returns the {@code CustomSchemaDef} documents of the current customer, ordered by {@code csd:defForTyp}.
 * <p>
 * The customer ID is resolved by calling the {@code DynamicFields.GetCustomerId} operation, which can be overridden to
 * provide custom resolution logic.
 * <p>
 * This operation backs the {@code nuxeo-document-suggestion} widgets, which submit {@code searchTerm} on every
 * keystroke along with {@code page} and {@code pageSize}. All three are honoured so the query stays bounded.
 *
 * @since 2025.1
 */
@Operation(id = GetDocumentTypes.ID, category = Constants.CAT_DOCUMENT,
        label = "Dynamic Fields: Get Document Types",
        description = "Returns the CustomSchemaDef documents of the current customer, "
                + "optionally filtered by a search term on the title.")
public class GetDocumentTypes {

    public static final String ID = "DynamicFields.GetDocumentTypes";

    /** Upper bound applied when the caller does not provide a page size. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    @Context
    protected CoreSession session;

    @Context
    protected AutomationService automationService;

    /** Free text typed by the user in the suggestion widget. */
    @Param(name = "searchTerm", required = false)
    protected String searchTerm;

    @Param(name = "pageSize", required = false)
    protected Integer pageSize;

    @Param(name = "page", required = false)
    protected Integer page;

    @OperationMethod
    public DocumentModelList run() {
        var query = new StringBuilder("SELECT * FROM CustomSchemaDef WHERE csd:customerId = ");
        query.append(NXQL.escapeString(resolveCustomerId()));
        query.append(" AND ecm:isTrashed = 0 AND ecm:isVersion = 0 AND ecm:isProxy = 0");
        if (StringUtils.isNotBlank(searchTerm)) {
            // The widget sends the raw user input: escape it and match as a prefix
            query.append(" AND dc:title ILIKE ");
            query.append(NXQL.escapeString(escapeLike(searchTerm.trim()) + "%"));
        }
        query.append(" ORDER BY csd:defForTyp ASC");

        long limit = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        long offset = page == null || page <= 0 ? 0 : page * limit;
        return session.query(query.toString(), null, limit, offset, false);
    }

    /** Escapes the NXQL LIKE wildcards so user input is matched literally. */
    protected static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    protected String resolveCustomerId() {
        try (var ctx = new OperationContext(session)) {
            return (String) automationService.run(ctx, GetCustomerId.ID);
        } catch (OperationException e) {
            throw new NuxeoException("Failed to resolve customer ID via " + GetCustomerId.ID, e);
        }
    }
}
