# Metabase Altertable Driver

An open-source [Metabase](https://www.metabase.com/) driver for querying an
[Altertable](https://altertable.ai/) Lakehouse catalog.

> [!IMPORTANT]
> This project is in early development. The read-only transport and result
> processing foundation is implemented, but schema synchronization, Metabase
> query execution wiring, and end-to-end Metabase validation are still in
> progress. It is not yet ready for production use.

## Project status

The driver currently provides:

- registration as a read-only Metabase SQL driver;
- validation and normalization of Altertable connection settings;
- authentication with either username/password or a basic token;
- streaming query execution through the Altertable Lakehouse Java SDK;
- cancellation and timeout primitives;
- conversion of Altertable result values to Metabase-friendly Java values;
- database-type mapping and conservative fallback type inference; and
- unit and mock-backed integration tests against Metabase `v0.61.2`.

Work remaining before the first usable release includes metadata
synchronization and MBQL-to-SQL execution integration.

## Read-only scope

The driver intentionally declares only read-oriented Metabase capabilities.
It does not expose write, DDL, upload, or database-management features.

This is a client-side safety boundary, not a replacement for server-side
authorization. Use Altertable credentials with the minimum permissions needed
to read the intended catalogs and schemas.

## Connection settings

Metabase will expose the following settings when the plugin is installed:

| Setting | Description |
| --- | --- |
| API URL | Altertable API endpoint; defaults to `https://api.altertable.ai` |
| Catalog | Lakehouse catalog to query |
| Default schema | Optional schema used when a query does not qualify a table |
| Username and password | Standard credentials; both values must be supplied together |
| Basic token | Alternative to username/password authentication |
| Compute size | `AUTO`, `XS`, `S`, `M`, `L`, or `XL` |
| Connection timeout | Time allowed to establish a connection, in seconds |
| Query timeout | Time allowed for a request, in seconds |

Supply exactly one authentication method. Secrets are passed to the SDK but
are omitted from validation errors and sanitized from SDK failures before an
error reaches Metabase.

## Development

### Requirements

- Java 21
- [Clojure CLI](https://clojure.org/guides/install_clojure) or Docker
- Docker for the mock-backed integration tests

The test dependency is pinned to Metabase `v0.61.2`. Supporting additional
Metabase versions will be evaluated as the driver approaches its first
release. The project consumes version `0.1.3` of the
[`altertable-lakehouse-java`](https://github.com/altertable-ai/altertable-lakehouse-java)
SDK from Maven Central.

### Run the tests

Start the Altertable mock server:

```sh
docker run --rm --name altertable-metabase-driver-test \
  -e ALTERTABLE_MOCK_USERS=testuser:testpass \
  -p 15000:15000 \
  ghcr.io/altertable-ai/altertable-mock:latest
```

Then run the complete test suite in another terminal:

```sh
ALTERTABLE_MOCK_URL=http://localhost:15000 clojure -X:test
```

The integration tests use the mock server credentials `testuser` and
`testpass`. Set `ALTERTABLE_MOCK_URL` when the mock is reachable at another
address. The unit tests do not require a running service.

Pull requests targeting `main` run the same suite in GitHub Actions with an
Altertable mock service.

### Build the plugin

The plugin is built with the driver tooling from the pinned Metabase version.
Clone Metabase and provide its path to the build script:

```sh
git clone --branch v0.61.2 --depth 1 \
  https://github.com/metabase/metabase.git ../metabase
METABASE_DIR=../metabase ./bin/build-driver.sh
```

The installable plugin is written to
`target/altertable.metabase-driver.jar`. Copy that file into the `plugins/`
directory of a self-hosted Metabase installation and restart Metabase.

## Releases

[Release Please](https://github.com/googleapis/release-please) derives semantic
versions and release notes from Conventional Commit messages on `main`. Its
release pull request updates `CHANGELOG.md` and the plugin version in
`resources/metabase-plugin.yaml`.

Merging the release pull request creates a `vX.Y.Z` GitHub release, builds the
driver against Metabase `v0.61.2`, and attaches both
`altertable.metabase-driver.jar` and its SHA-256 checksum. The release workflow
also accepts an existing tag through manual dispatch so a failed artifact
build or upload can be retried safely.

Repository administrators must allow GitHub Actions to create pull requests
in the repository's workflow-permission settings so Release Please can open
and update its release pull request with `GITHUB_TOKEN`.

## Architecture

- `metabase.driver.altertable` registers the driver and its capabilities.
- `metabase.driver.altertable.client` owns connection validation, SDK client
  construction, query requests, streaming, cancellation, and error handling.
- `metabase.driver.altertable.results` maps Altertable metadata and values to
  Metabase result rows and base types.
- `resources/metabase-plugin.yaml` defines the Metabase plugin and connection
  form.

The implementation follows Metabase's
[driver development guide](https://www.metabase.com/docs/latest/developers-guide/drivers/start)
and uses the
[`metabase/sudoku-driver`](https://github.com/metabase/sudoku-driver) project
as a small reference implementation.

## Contributing

Issues and pull requests are welcome. Please keep changes focused, add tests
for behavior changes, and run `clojure -X:test` before opening a pull request.
Use Conventional Commit messages (for example, `fix: handle empty results`) so
Release Please can determine the next version and generate useful release
notes.
For security-sensitive reports, avoid publishing credentials, tokens, query
results, or other private deployment details in a public issue.

## License

This project is available under the [MIT License](LICENSE).
