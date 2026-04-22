# Nuxeo Labs Dynamic Fields — Agent Guide

## Project

Nuxeo LTS 2025 plugin (Java 21, Maven) implementing an Entity-Attribute-Value (EAV) pattern for multi-tenant dynamic fields on documents. Uses OpenSearch nested queries for correlated search.

- **Parent**: `org.nuxeo:nuxeo-parent:2025.16`
- **GroupId**: `nuxeo.labs.dynamic.fields`
- **Version**: `2025.1.0-SNAPSHOT`

## Modules

| Module | Purpose |
|--------|---------|
| `nuxeo-labs-dynamic-fields-core` | Java code, OSGI-INF component XMLs, Web UI elements, tests |
| `nuxeo-labs-dynamic-fields-package` | Nuxeo Marketplace package (assembly, install templates incl. `dynamic-fields-opensearch`) |

Key paths in the core module:
- Java sources: `nuxeo-labs-dynamic-fields-core/src/main/java/nuxeo/labs/dynamic/fields/`
- Component XMLs: `nuxeo-labs-dynamic-fields-core/src/main/resources/OSGI-INF/`
- Bundle manifest: `nuxeo-labs-dynamic-fields-core/src/main/resources/META-INF/MANIFEST.MF`
- Tests: `nuxeo-labs-dynamic-fields-core/src/test/java/nuxeo/labs/dynamic/fields/`
- Web UI widgets: `nuxeo-labs-dynamic-fields-core/src/main/resources/web/nuxeo.war/ui/`

## Build & Test Commands

```bash
# Full build (all modules)
mvn clean install

# Build skipping tests
mvn clean install -DskipTests

# Run tests only in core module
mvn test -pl nuxeo-labs-dynamic-fields-core

# Run a single test class
mvn test -pl nuxeo-labs-dynamic-fields-core -Dtest=TestDynamicFieldsSearchPageProvider
```

No CI workflows, no linter, no formatter configured in this repo. The `nuxeo-parent` POM may enforce Spotless but `nuxeo.skip.enforcer=true` is set.

## Current Code

- **`GetCustomerId`** — operation returning current user's customer ID (configurable via `dynamicfields.customerid.chain` config property; defaults to hard-coded `ABCD-1234`)
- **`GetDocumentTypes`** — operation returning `CustomSchemaDef` documents for current customer
- **`DynamicFieldsSearchPageProvider`** — custom PageProvider building OpenSearch nested queries from `dynf_search` named parameter
- OSGI-INF contributions: schemas (`custom-schema-def`, `dynamic-fields`), document types (`CustomSchemaDef`, `CustomSchemaDefContainer`), facet (`DynamicFields`), directories (`dynf_field_types`), page providers

## Adding New Code

### New Automation Operation

1. Create the Java class annotated with `@Operation(id = "...")` containing `@OperationMethod`
2. Create or update an OSGI-INF XML to register it via `<extension target="org.nuxeo.ecm.core.operation.OperationServiceComponent" point="operations">`
3. List the XML in `META-INF/MANIFEST.MF` under `Nuxeo-Component:` (continuation lines start with a single space)
4. MANIFEST.MF **must end with a trailing newline** or the last header is silently dropped

### New Service (Component + Extension Point)

1. Create a service interface and a component class extending `DefaultComponent`
2. Declare in OSGI-INF XML with `<component>`, `<implementation>`, `<service>`, optional `<extension-point>`
3. Register in MANIFEST.MF
4. Look up at runtime: `Framework.getService(MyService.class)`

### New Schema or Document Type

- Schema XSD files go in `src/main/resources/schema/`
- Register via `<extension target="org.nuxeo.ecm.core.schema.TypeService" point="schema">`
- Document types via `point="doctype"`

### Dependencies

Module POMs declare dependencies **without `<version>` tags** — versions are managed by `nuxeo-parent`. The core module depends on `nuxeo-automation-core` (compile), `nuxeo-core-search` + OpenSearch deps (provided), and `nuxeo-automation-test` + `nuxeo-core-test` (test).

## Critical Pitfalls

These will cause silent failures or build errors if ignored:

- **NOT Spring**: No `@Autowired`, `@Component`, `@Service`. Use Nuxeo's component model (`DefaultComponent`, `Framework.getService()`)
- **Jakarta, not javax**: All imports use `jakarta.*` namespace (`jakarta.inject`, `jakarta.ws.rs`, etc.)
- **JUnit 4 only**: Use `@RunWith(FeaturesRunner.class)` + `@Features(...)` + `@Deploy(...)`. No JUnit 5
- **Log4j2 only**: `LogManager.getLogger(MyClass.class)`. No SLF4J, no `System.out.println`
- **No raw Jackson for REST**: Use Nuxeo's `MarshallerRegistry` framework
- **No JPA**: Document storage uses `CoreSession` / `DocumentModel` API

## Testing Patterns

```java
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("nuxeo.labs.dynamic.fields.nuxeo-labs-dynamic-fields-core")
public class TestMyOperation {

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automationService;

    @Test
    public void testSomething() throws Exception {
        // ...
    }
}
```

- `@Deploy("bundle.symbolic.name")` — the symbolic name is in MANIFEST.MF (`Bundle-SymbolicName`)
- `@Deploy("bundle:OSGI-INF/test-contrib.xml")` — deploy test-only XML contributions
- `TransactionalFeature.nextTransaction()` — flush async work between steps

## Local References (optional)

If the Nuxeo LTS 2025 source code or other plugin examples are available locally, working with local files is faster and avoids network calls. Ask the user for local paths before falling back to GitHub.

Prompt the user with:

> "Do you have the Nuxeo LTS 2025 source code cloned locally? If so, what is the path? (e.g., `~/GitHub/nuxeo/nuxeo-lts`). Otherwise, I'll use the GitHub repository."

Similarly, for plugin examples:

> "Do you have any Nuxeo plugin examples locally? (e.g., `nuxeo-labs-dynamic-fields`, `nuxeo-dynamic-asset-transformation`). If so, what are the paths? Otherwise, I'll browse them on GitHub."

### Fallback URLs

- Nuxeo LTS 2025: https://github.com/nuxeo/nuxeo-lts (branch `2025`)
- Plugin examples: https://github.com/nuxeo-sandbox/nuxeo-labs-signature-webui, https://github.com/nuxeo-sandbox/nuxeo-labs-pdf-toolkit

## Code Style

### Java

- 4-space indent, **no tabs**, no trailing spaces, K&R braces, ~120 char lines
- Use modern Java: `var`, records, pattern matching `instanceof`, switch expressions, text blocks, `String.formatted()`
- **No wildcard imports**. Import order: static, `java.*`, `jakarta.*`, `org.*`, `com.*`
- Always use braces for `if`/`else` blocks (even single-line)
- No `final` on method parameters or local variables
- No `private` methods/fields (use `protected`); exceptions: `log` and `serialVersionUID`
- No `final` classes or methods (hinders extensibility)
- Use `i++` (not `++i`) for simple increments
- Use `Objects.requireNonNull` for null checks
- Logging: parameterized messages `log.debug("Processing: {}", docId)`, use `if (log.isDebugEnabled())` for non-constant messages
- Javadoc first sentence: 3rd person verb phrase ending with period (*Gets the foobar.* not *Get the foobar*)
- `@since 2025.XX` on new public API. No `@author` tag
- License header: Apache 2.0 with current year and `Contributors:` section

### XML (OSGI-INF, POMs, XSD, HTML)

- **2-space indent**, no tabs, 120 char line width
- Self-closing tags: add space before `/>` (e.g., `<property name="foo" />`)

### JavaScript (Automation Scripting, Web UI)

- **4-space indent**, no tabs
- Nuxeo Automation Scripting uses **ECMAScript 5** (no `let`/`const`, no arrow functions, no template literals)
- Automation operations are called as global functions: `Document.Create(input, type, name, properties)`, `Document.Query(null, {"query": queryString})`, etc. — always pass named parameters as an object

### Markdown (README, docs)

- Use GitHub alert syntax for notes, warnings, tips, etc.:
  ```
  > [!NOTE]
  > Content here

  > [!TIP]
  > Content here

  > [!IMPORTANT]
  > Content here

  > [!WARNING]
  > Content here

  > [!CAUTION]
  > Content here
  ```
- See [GitHub alerts documentation](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax#alerts)
