# Nuxeo Labs Dynamic Fields — Agent Guide

## Project

Nuxeo LTS 2025 plugin (Java 21, Maven). Currently a scaffold with a placeholder operation; real functionality to be added.

- **Parent**: `org.nuxeo:nuxeo-parent:2025.16`
- **GroupId**: `nuxeo.labs.dynamic.fields`
- **Version**: `2025.1.0-SNAPSHOT`

## Modules

| Module | Purpose |
|--------|---------|
| `nuxeo-labs-dynamic-fields-core` | Java code, OSGI-INF component XMLs, tests |
| `nuxeo-labs-dynamic-fields-package` | Nuxeo Marketplace package (assembly, install templates) |

Key paths in the core module:
- Java sources: `nuxeo-labs-dynamic-fields-core/src/main/java/nuxeo/labs/dynamic/fields/`
- Component XMLs: `nuxeo-labs-dynamic-fields-core/src/main/resources/OSGI-INF/`
- Bundle manifest: `nuxeo-labs-dynamic-fields-core/src/main/resources/META-INF/MANIFEST.MF`
- Tests: `nuxeo-labs-dynamic-fields-core/src/test/java/nuxeo/labs/dynamic/fields/`

## Build & Test Commands

```bash
# Full build (all modules)
mvn clean install

# Build skipping tests
mvn clean install -DskipTests

# Run tests only in core module
mvn test -pl nuxeo-labs-dynamic-fields-core

# Run a single test class
mvn test -pl nuxeo-labs-dynamic-fields-core -Dtest=TestDummyOpForTest
```

No CI workflows, no linter, no formatter configured in this repo. The `nuxeo-parent` POM may enforce Spotless but `nuxeo.skip.enforcer=true` is set.

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

Module POMs declare dependencies **without `<version>` tags** — versions are managed by `nuxeo-parent`. The core module currently depends on `nuxeo-automation-core` (compile) and `nuxeo-automation-test` (test).

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

## Reference Examples

LTS 2025 plugin examples (verify parent version starts with `2025.`):

- Simple: `~/Desktop/example-plugin`
- Published: `~/GitHub/nuxeo-sandbox/nuxeo-labs-pdf-toolkit`, `~/GitHub/nuxeo-sandbox/nuxeo-labs-content-intelligence-connector`, `~/GitHub/nuxeo-sandbox/nuxeo-labs-multi-nuxeoapps`
- Nuxeo LTS 2025 source: `~/GitHub/nuxeo/nuxeo-sources-lts-2025`

## Java Style

- 4-space indent, K&R braces, ~120 char lines
- Use modern Java: `var`, records, pattern matching `instanceof`, switch expressions, text blocks, `String.formatted()`
- License header: Apache 2.0 with current year and `Contributors:` section
- `@since 2025.XX` on new public API
