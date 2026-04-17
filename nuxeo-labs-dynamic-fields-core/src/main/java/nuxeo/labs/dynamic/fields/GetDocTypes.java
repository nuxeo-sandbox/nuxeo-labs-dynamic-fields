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

import java.util.TreeSet;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModelList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Returns a JSON blob containing a sorted array of distinct {@code csd:defForTyp} values
 * from all {@code CustomSchemaDef} documents for the given customer.
 *
 * @since 2025.1
 */
@Operation(id = GetDocTypes.ID, category = Constants.CAT_DOCUMENT,
        label = "Dynamic Fields: Get Doc Types",
        description = "Returns a JSON array of distinct custom doc types defined for a customer.")
public class GetDocTypes {

    public static final String ID = "DynamicFields.getDocTypes";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Context
    protected CoreSession session;

    @Param(name = "customerId")
    protected String customerId;

    @OperationMethod
    public Blob run() throws Exception {
        var query = "SELECT * FROM CustomSchemaDef"
                + " WHERE csd:customerId = '%s'".formatted(customerId.replace("'", "\\'"))
                + " AND ecm:isTrashed = 0 AND ecm:isVersion = 0 AND ecm:isProxy = 0";

        DocumentModelList docs = session.query(query);

        // TreeSet for natural sort order and deduplication
        var types = new TreeSet<String>();
        for (var doc : docs) {
            var defForTyp = (String) doc.getPropertyValue("csd:defForTyp");
            if (defForTyp != null && !defForTyp.isBlank()) {
                types.add(defForTyp);
            }
        }

        ArrayNode array = MAPPER.createArrayNode();
        types.forEach(array::add);

        return Blobs.createJSONBlob(array.toString());
    }
}
