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

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModelList;

/**
 * Returns all {@code CustomSchemaDef} documents for the current customer,
 * ordered by {@code csd:defForTyp}.
 * <p>
 * The customer ID is currently hard-coded for testing purposes.
 *
 * @since 2025.1
 */
@Operation(id = GetDocumentTypes.ID, category = Constants.CAT_DOCUMENT,
        label = "Dynamic Fields: Get Document Types",
        description = "Returns all CustomSchemaDef documents for the current customer.")
public class GetDocumentTypes {

    public static final String ID = "DynamicFields.GetDocumentTypes";

    // Hard-coded for testing — will later be read from user profile
    private static final String CUSTOMER_ID = "ABCD-1234";

    @Context
    protected CoreSession session;

    @OperationMethod
    public DocumentModelList run() {
        var query = "SELECT * FROM CustomSchemaDef"
                + " WHERE csd:customerId = '%s'".formatted(CUSTOMER_ID.replace("'", "\\'"))
                + " AND ecm:isTrashed = 0 AND ecm:isVersion = 0 AND ecm:isProxy = 0"
                + " ORDER BY csd:defForTyp ASC";
        return session.query(query);
    }
}
