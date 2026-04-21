# nuxeo-labs-dynamic-fields

A Nuxeo LTS 2025 plugin that implements an Entity-Attribute-Value (EAV) pattern, allowing customers to define custom fields per document type without creating actual schema fields in the database. Designed for multi-tenant scenarios where each customer can have their own set of dynamic fields on any document type.

> [!IMPORTANT]
> (See below for details)
> * This requires to  use the Nested File Type of Elastic/Opensearch, which means **you must deploy the `dynamic-fields-opensearch` configuration template**.
> * We implement this **only for OpenSearch**, but you can easily also create the Elasticsearch part of you need


## Concepts

### Schemas

- **`custom-schema-def`** (prefix `csd`): Defines which dynamic fields exist for a given customer and document type. Each field has a `fieldName` and a `fieldTyp` (validated against the `dynf_field_types` vocabulary: string, integer, double, boolean, date, blob).
- **`dynamic-fields`** (prefix `dynf`): Carries the actual dynamic field values on documents. Values are stored in `dynf:values`, a multivalued complex field where each entry has a `fieldName` and typed value columns (`stringValue`, `integerValue`, `doubleValue`, `booleanValue`, `dateValue`, `blobValue`). Only the column matching the field type is populated. The `dynf:schemaDef` field references the `CustomSchemaDef` document that defines the field structure.

### Document Types and Facet

- **`CustomSchemaDef`**: A document type that holds field definitions for a specific customer and document type. One per customer per document type combination. Stored inside a `CustomSchemaDefContainer` folder.
- **`DynamicFields` facet**: Add this facet to any document type (via Studio or XML contribution) to give it dynamic field capabilities. The facet carries the `dynamic-fields` schema.

### Operations

- **`DynamicFields.GetCustomerId`**: Returns the customer ID for the current user. The default implementation returns a hard-coded value (`ABCD-1234`) for development and testing. **You must configure this** in production — see [Customer ID Resolution](#customer-id-resolution) below.
- **`DynamicFields.GetDocumentTypes`**: Returns all `CustomSchemaDef` documents for the current customer (resolved via `GetCustomerId`). Used internally by the UI widgets — not intended for direct use.

## Customer ID Resolution

Both schemas use a `customerId` field to associate data with a specific customer. The customer ID is resolved at runtime by the `DynamicFields.GetCustomerId` operation.

By default, if no configuration is provided, the operation returns a hard-coded value (`ABCD-1234`) and logs a warning once. To provide your own resolution logic:

1. Create an **Automation Scripting** in Nuxeo Studio (e.g. named `utils_GetCustomerId`) that returns the customer ID as a string. For example, if the customer ID is stored in the user's `company` field:

    ```javascript
    // Automation Scripting name: utils_GetCustomerId
    function run(input, params) {
      return currentUser.getPropertyValue("user:company");
    }
    ```

2. Create an **XML Extension** in Nuxeo Studio to register the chain. Use the **full operation ID** (Studio Automation Scripting IDs are automatically prefixed with `javascript.`):

    ```xml
    <extension target="org.nuxeo.runtime.ConfigurationService" point="configuration">
      <property name="dynamicfields.customerid.chain">javascript.utils_GetCustomerId</property>
    </extension>
    ```

The configured chain is called every time the customer ID is needed (e.g. when listing schema definitions or rendering widgets).

## ACLs and Permissions

This plugin does **not** manage permissions. It is the responsibility of the developer/administrators/etc. using this plugin to set up proper ACLs so that, typically:

- Only some customer's users can only **create, read, update, and delete** their own `CustomSchemaDef` documents
  - (Or a higher level admin.)
- Each customer's users can read these `CustomSchemaDef` (so they are displayed in a list for example)
- Each customer's users can only access documents that belong to them
- Admin/operator users who need cross-customer access have the appropriate permissions

Typically, you would organize `CustomSchemaDefContainer` folders with ACLs restricting access per customer, and apply similar permission policies to the documents carrying the `DynamicFields` facet.

## Using Dynamic Fields in Document Layouts

### Edit Layout

Import the `dynamic-fields-edit` widget and add it to your document's edit layout. For example, to add dynamic fields to the Picture edit layout:

> [!IMPORTANT]
> Do not forget the `{{ }}` double binding.

```html
<link rel="import" href="../../forms/dynamic-fields-edit.html">

<dom-module id="nuxeo-picture-edit-layout">
  <template>
    <!-- ... default Picture edit widgets ... -->

    <dynamic-fields-edit document="{{document}}"></dynamic-fields-edit>
  </template>
  <!-- ... -->
</dom-module>
```

The widget handles schema definition selection, renders the appropriate input for each field type, and manages the `dynf:values` property on the document.

### View / Metadata Layout

Import the `dynamic-fields-view` widget and add it to your document's view or metadata layout:

```html
<link rel="import" href="../../forms/dynamic-fields-view.html">

<dom-module id="nuxeo-picture-metadata-layout">
  <template>
    <!-- ... default metadata widgets ... -->

    <dynamic-fields-view document="[[document]]"></dynamic-fields-view>
  </template>
  <!-- ... -->
</dom-module>
```

## Searching Dynamic Fields

### The Problem

NXQL wildcard queries on complex multivalued fields are **not correlated**. A query like `dynf:values/*/fieldName = 'color' AND dynf:values/*/stringValue = 'blue'` can match documents where those values are in **different** entries of the array. This makes standard NXQL unreliable for searching dynamic field values.

### The Solution: OpenSearch Nested Queries

This plugin provides:

1. An **OpenSearch index template** (`dynamic-fields-opensearch`) that maps `dynf:values` as a `nested` type, enabling correlated queries within the same array entry.
2. A **custom PageProvider** (`DynamicFieldsSearchPageProvider`) that builds OpenSearch nested queries from search criteria, while letting standard NXQL predicates and aggregates work normally.
3. A **search form widget** (`dynf-search-form`) that renders dynamic field inputs and builds the search criteria automatically.

### Setting Up OpenSearch

Add the following to your `nuxeo.conf` to activate the nested mapping template:

```
nuxeo.append.templates.dynf=dynamic-fields-opensearch
```

(The key `dynf` can be any name you choose — see [nuxeo.append.templates documentation](https://doc.nuxeo.com/nxdoc/configuration-parameters-index-nuxeoconf/#nuxeoappendtemplates).)

Then re-index your content from the Nuxeo Admin > Elasticsearch page.

> **Note**: On a fresh deployment (where `dynf:values` was never previously indexed), a standard re-index is sufficient. If you previously had `dynf:values` indexed as a non-nested type (e.g. during development), you must **drop and recreate the index** before re-indexing — OpenSearch does not allow changing a field from `object` to `nested` on an existing index. You can do this by stopping Nuxeo, deleting the index (`curl -X DELETE http://<opensearch-host>:9200/nuxeo`), then restarting Nuxeo and re-indexing.

### Creating a Search in Studio

1. In **Nuxeo Studio**, create a new Page Provider:
   - Set the **Page Provider Class** to `nuxeo.labs.dynamic.fields.DynamicFieldsSearchPageProvider`
   - Add your base query predicates (fulltext, `dc:created BETWEEN`, tags, etc.)
   - Add any aggregates you need
   - Configure default sort
   - The plugin handles the dynamic field search criteria on top of whatever NXQL Studio generates

2. In **Studio Designer**, create the search form and result layouts for your page provider. In the search form layout HTML, import and add the dynamic fields search widget:

> [!IMPORTANT]
> Do not forget the `{{ }}` double binding.

```html
<link rel="import" href="../../forms/dynf-search-form.html">

<dom-module id="my-custom-search-form">
  <template>
    <!-- Standard Studio-generated widgets for predicates/aggregates -->
    <nuxeo-input role="widget" label="Full text" value="{{searchTerm}}" ...></nuxeo-input>
    <nuxeo-date-picker role="widget" label="Created after" ...></nuxeo-date-picker>
    <!-- ... other standard widgets ... -->

    <!-- Dynamic fields search -->
    <dynf-search-form params="{{params}}"></dynf-search-form>
  </template>
  <!-- ... -->
</dom-module>
```

The `dynf-search-form` widget:
- Lets the user select a `CustomSchemaDef` to determine which dynamic fields are searchable
- Renders an input for each field with the appropriate widget (text, number, checkbox, date picker)
- Shows an operator selector (=, <, <=, >, >=, and "like" for strings)
- Automatically builds and sets the `dynf_search` named parameter on `params`, which flows to the PageProvider alongside all standard Studio predicates

The plugin also registers a default `dynf_search` page provider (in `dynamic-fields-pageproviders.xml`) that can be used directly via REST API without Studio configuration.

## Tuning/Overriding

To tune the misc. layouts provided, just recreate the same at the same location in the `ui` folder.

## Translation

The plugin ships with English and French labels for the document types and the field type values.

To change or adapt these labels — for example to add translations for other languages — add the translation keys to your Studio project, in Designer. You can find the [i18n folder](nuxeo-labs-dynamic-fields-core/src/main/resources/web/nuxeo.war/ui/i18n/).

## How to build
```bash
git clone https://github.com/nuxeo-sandbox/nuxeo-labs-dynamic-fields
cd nuxeo-labs-dynamic-fields
mvn clean install
```

To skip unit testing, add `-DskipTests`

## Support
**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning
resources.

This is a moving project (no API maintenance, no deprecation process, etc.) If any of these solutions are found to be
useful for the Nuxeo Platform in general, they will be integrated directly into platform, not maintained here.

## Nuxeo Marketplace
[here](https://connect.nuxeo.com/nuxeo/site/marketplace/package/nuxeo-labs-dynamic-fields)

## License
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

## About Nuxeo
Nuxeo Platform is an open source highly scalable, cloud-native, enterprise content management product with rich multimedia support, written in Java. Data can be stored in both SQL & NoSQL databases.

The development of the Nuxeo Platform is mostly done by Nuxeo employees with an open development model.

The source code, documentation, roadmap, issue tracker, testing, benchmarks are all public.

More information is available at [Hyland/Nuxeo](https://www.hyland.com/en/solutions/products/nuxeo-platform).
