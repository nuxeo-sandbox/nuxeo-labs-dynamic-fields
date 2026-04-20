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
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.runtime.api.Framework;

/**
 * Returns the customer ID for the current user.
 * <p>
 * The customer ID is resolved by calling the automation chain/script
 * configured via the {@code dynamicfields.customerid.chain} property.
 * This property is typically set via an XML extension in Nuxeo Studio:
 * <pre>
 * &lt;extension target="org.nuxeo.runtime.ConfigurationService" point="configuration"&gt;
 *   &lt;property name="dynamicfields.customerid.chain"&gt;javascript.DynamicFields_GetCustomerId&lt;/property&gt;
 * &lt;/extension&gt;
 * </pre>
 * <p>
 * If the property is not set, a default hard-coded value ({@code ABCD-1234})
 * is returned and a warning is logged once.
 * <p>
 * Example Studio Automation Scripting (named {@code DynamicFields_GetCustomerId}):
 * <pre>
 * function run(input, params) {
 *   return currentUser.getPropertyValue("user:company");
 * }
 * </pre>
 *
 * @since 2025.1
 */
@Operation(id = GetCustomerId.ID, category = Constants.CAT_SERVICES,
        label = "Dynamic Fields: Get Customer ID",
        description = "Returns the customer ID for the current user. "
                + "Configure dynamicfields.customerid.chain via an XML extension "
                + "to provide your own resolution logic.")
public class GetCustomerId {

    public static final String ID = "DynamicFields.GetCustomerId";

    /** The nuxeo.conf property specifying the automation chain to call. */
    public static final String CHAIN_PROPERTY = "dynamicfields.customerid.chain";

    private static final String DEFAULT_CUSTOMER_ID = "ABCD-1234";

    private static final Logger log = LogManager.getLogger(GetCustomerId.class);

    private static volatile boolean warnLogged = false;

    @Context
    protected CoreSession session;

    @Context
    protected AutomationService automationService;

    @OperationMethod
    public String run() {
        String chainId = Framework.getProperty(CHAIN_PROPERTY);

        if (StringUtils.isBlank(chainId)) {
            if (!warnLogged) {
                warnLogged = true;
                log.warn("No '{}' property configured in nuxeo.conf. "
                        + "Using default customer ID '{}'. "
                        + "Set this property to the full ID of your automation chain/script "
                        + "(e.g. 'javascript.DynamicFields_GetCustomerId').",
                        CHAIN_PROPERTY, DEFAULT_CUSTOMER_ID);
            }
            return DEFAULT_CUSTOMER_ID;
        }

        try {
            var ctx = new OperationContext(session);
            Object result = automationService.run(ctx, chainId);
            if (result instanceof String customerId) {
                return customerId;
            }
            throw new NuxeoException(
                    "Chain '%s' must return a String, got: %s".formatted(
                            chainId, result != null ? result.getClass().getName() : "null"));
        } catch (OperationException e) {
            throw new NuxeoException(
                    "Failed to resolve customer ID via chain '%s'".formatted(chainId), e);
        }
    }
}
