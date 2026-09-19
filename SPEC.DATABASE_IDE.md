Below is a spec intended to be handed directly to Codex in the Kode repository. It deliberately defines behavior and architecture rather than forcing implementation details that may conflict with the existing TUI framework.

# Kode — JDBC Database Explorer and SQL Console

## 1. Objective

Add integrated database tooling to Kode.

Kode must allow the user to:

* define JDBC data sources;
* dynamically obtain JDBC drivers when necessary;
* connect/disconnect data sources;
* persist data-source configuration locally;
* introspect database metadata;
* browse database objects through a tree;
* refresh all or part of that metadata tree;
* inspect database objects;
* open SQL console sessions associated with a data source;
* edit SQL in Kode's normal content editor;
* execute SQL from those editors;
* display query results in a bottom panel below the editor;
* display JDBC errors, update counts, warnings, execution duration, and similar execution information.

The database functionality must remain separated into:

```text
TUI
 ↓
Database UI/controller layer
 ↓
Database service/API
 ↓
JDBC connection + metadata + query services
 ↓
JDBC driver abstraction
 ↓
Database
```

Do not put JDBC calls directly in TUI components.

The implementation should use Kode's existing editor, tab, focus, tree, menu, popup/dialog, keyboard, scrolling, theme, status and panel infrastructure wherever possible.

Before implementing anything, inspect the repository to identify those existing abstractions and integrate into them rather than creating parallel infrastructure.

---

# 2. Main UI concept

The reference behavior is conceptually similar to the database window in JetBrains IDEs, adapted to Kode's terminal UI.

The main Kode layout becomes:

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ Files  Project  Git  Database  About  Settings  Help                       │
├──────────────────────┬───────────────────────────────────────────────────────┤
│ DATABASE             │ query@local-postgres.sql                             │
│                      │                                                       │
│ + New                │ SELECT id, name, created                             │
│ ↻ Refresh            │ FROM customer                                        │
│                      │ WHERE active = true                                  │
│ ▼ local-postgres ●   │ ORDER BY name;                                       │
│   ▼ postgres         │                                                       │
│     ▼ public         │                                                       │
│       ▼ Tables       │                                                       │
│         customer     │                                                       │
│         invoice      │                                                       │
│       ▼ Views        │                                                       │
│         open_invoice │                                                       │
│       ▶ Functions    │                                                       │
│       ▶ Procedures   │                                                       │
│                      ├───────────────────────────────────────────────────────┤
│ ▶ oracle-dev ○       │ Results 1 │ Messages │ Output                         │
│ ▶ h2-local ○         ├──────┬─────────────────────┬──────────────────────────┤
│                      │ ID   │ NAME                │ CREATED                  │
│                      ├──────┼─────────────────────┼──────────────────────────┤
│                      │ 12   │ Alice               │ 2026-09-15               │
│                      │ 18   │ Bob                 │ 2026-09-17               │
├──────────────────────┴───────────────────────────────────────────────────────┤
│ DB: local-postgres ●  public   Rows:2   31 ms                     INS UTF-8 │
└──────────────────────────────────────────────────────────────────────────────┘
```

The three main elements are therefore:

```text
LEFT
Database explorer

CENTER/TOP
Normal Kode content editor containing SQL

CENTER/BOTTOM
SQL result/output panel
```

---

# 3. Left-side Database panel

Add a database explorer as another left-side tool/panel.

Do not permanently consume additional horizontal space.

The existing left-side area should be switchable between at least:

```text
Files
Database
```

Depending on current TUI conventions this can appear as:

```text
[ Files ] [ Database ]
```

or a panel selector/menu.

The panel must be keyboard accessible.

Suggested conceptual panel header:

```text
Database                       [+] [↻]
─────────────────────────────────────
▼ postgres-dev                 ●
  ▼ postgres
    ▼ public
      ▼ Tables
      ▶ Views
      ▶ Functions
      ▶ Procedures

▶ oracle-production            ○
▶ local-h2                     ○
```

Connection status:

```text
● connected
○ disconnected
◌ connecting
× connection/error state
```

Use ASCII alternatives when the terminal/font does not support those glyphs.

---

# 4. Main Database menu

Add a top-level menu:

```text
Database
```

Suggested contents:

```text
Database
 ├─ New Data Source...
 ├─ Connect
 ├─ Disconnect
 ├─ Edit Data Source...
 ├─ Duplicate Data Source...
 ├─ Remove Data Source...
 ├────────────────────────────
 ├─ New SQL Console
 ├─ Open SQL File...
 ├────────────────────────────
 ├─ Refresh
 ├─ Refresh All
 ├────────────────────────────
 ├─ Commit
 ├─ Rollback
 ├─ Auto Commit             ✓
 ├────────────────────────────
 └─ Drivers...
```

Disable commands that do not make sense for the current context.

For example:

```text
Disconnect
```

is disabled for a disconnected data source.

---

# 5. Database explorer context menu

Right-click/context-action/keyboard-menu on a data source:

```text
local-postgres
 ├─ Connect / Disconnect
 ├─ New SQL Console
 ├─ Refresh
 ├────────────────────
 ├─ Properties...
 ├─ Duplicate...
 ├─ Rename...
 ├─ Remove...
 ├────────────────────
 └─ Driver...
```

On a schema:

```text
public
 ├─ Set as Current Schema
 ├─ New SQL Console
 ├─ Refresh
 └─ Properties
```

On a database object:

```text
customer
 ├─ Inspect
 ├─ Show Columns
 ├─ Show DDL
 ├─ Select Rows
 ├─ Copy Qualified Name
 └─ Refresh
```

Vendor-specific actions may subsequently be contributed by database adapters.

---

# 6. Creating a data source

`New Data Source...` opens a modal/form.

Initial screen:

```text
┌──────────────── New Data Source ────────────────┐
│                                                 │
│ Name:       [ postgres-dev                    ] │
│                                                 │
│ Database:   [ PostgreSQL                     ▼] │
│ Driver:     [ PostgreSQL JDBC                ▼] │
│                                                 │
│ JDBC URL:                                      │
│ [ jdbc:postgresql://localhost:5432/mydb       ] │
│                                                 │
│ User:       [ developer                       ] │
│ Password:   [ ********                        ] │
│                                                 │
│ Properties...                                  │
│                                                 │
│              [ Test ] [ Save ] [ Cancel ]      │
└─────────────────────────────────────────────────┘
```

A more advanced form may expose:

```text
General | Properties | Driver | SSH/SSL
```

Do not implement SSH tunnelling merely because that tab exists in the reference screenshots unless Kode already has appropriate infrastructure.

For the first implementation, JDBC connection properties are sufficient.

---

# 7. Data-source model

Conceptual model:

```kotlin
DataSourceDefinition {
    id
    name

    driverId
    driverClass
    driverCoordinates
    driverJar

    jdbcUrl

    username
    credentialReference

    properties: Map<String, String>

    defaultCatalog
    defaultSchema

    autoCommit
    readOnly

    metadataOptions
}
```

`id` must be stable and independent of the display name so the user can rename a connection without invalidating SQL-console bindings.

Example persisted configuration:

```json
{
  "id": "13a9239c-...",
  "name": "local-postgres",
  "driver": {
    "id": "postgresql",
    "class": "org.postgresql.Driver",
    "coordinates": "org.postgresql:postgresql:42.7.8"
  },
  "jdbcUrl": "jdbc:postgresql://localhost:5432/kode",
  "username": "dev",
  "credentialReference": "local-postgres.password",
  "properties": {
    "applicationName": "Kode"
  },
  "defaultSchema": "public",
  "autoCommit": true
}
```

Do not store runtime metadata inside the same connection-definition object.

---

# 8. Persistence

Connection definitions must be stored in Kode's designated local parameters/configuration directory.

Use the existing Kode configuration-location mechanism. Do not hard-code `$HOME`.

Suggested logical structure:

```text
<kode-config>/
    database/
        datasources.json
        credentials.properties
        state.json
```

or individual files:

```text
<kode-config>/
    database/
        datasources/
            <uuid>.json
        credentials/
            <uuid>.secret
```

The implementation should follow existing Kode configuration conventions.

Passwords must not be stored inside project files.

If credentials are persisted as files, restrict their permissions where supported:

```text
0600
```

If Kode already has a secret/credential abstraction, use it.

---

# 9. JDBC driver manager

Create a dedicated driver subsystem.

Conceptually:

```text
JdbcDriverManager
    DriverCatalog
    DriverResolver
    DriverDownloader
    DriverLoader
    DriverInstance
```

A driver definition contains:

```text
id
displayName
databaseFamily
driverClass
defaultMavenCoordinates
defaultJdbcUrlTemplate
urlPrefixes
```

For example:

```text
PostgreSQL
org.postgresql.Driver
org.postgresql:postgresql:<version>

H2
org.h2.Driver
com.h2database:h2:<version>

SQLite
org.sqlite.JDBC
org.xerial:sqlite-jdbc:<version>

Oracle
oracle.jdbc.OracleDriver
com.oracle.database.jdbc:ojdbc11:<version>

MariaDB
org.mariadb.jdbc.Driver

MySQL
com.mysql.cj.jdbc.Driver

HSQLDB
org.hsqldb.jdbc.JDBCDriver
```

Do not limit the architecture to that initial catalog.

There must also be:

```text
Generic JDBC Driver
```

where the user manually supplies:

```text
driver class
Maven coordinates
or
local JAR path
```

---

# 10. Automatic driver downloading

When a required driver is absent:

```text
Driver PostgreSQL JDBC is not installed.

[Maven coordinate]
org.postgresql:postgresql:42.7.x

[Download] [Choose JAR] [Cancel]
```

The downloaded artifact goes into Kode's plugin storage, logically:

```text
<kode-plugins>/
    jdbc/
        postgresql/
            42.7.x/
                postgresql-42.7.x.jar
```

Use Kode's actual plugin directory abstraction rather than assuming this exact filesystem layout.

Requirements:

* don't re-download an already installed driver;
* downloads must not block the TUI thread;
* show download progress/status;
* handle offline mode cleanly;
* report HTTP/Maven resolution errors;
* support local JAR installation;
* remember installed versions;
* allow several driver versions to coexist.

Do not include JDBC JARs in Kode's application JAR.

---

# 11. JDBC class loading

Drivers must be dynamically loadable.

Prefer per-driver isolated class loaders.

Conceptually:

```text
DriverClassLoader
   └── JDBC driver JAR(s)
```

Avoid polluting Kode's primary application classpath.

In particular, account for multiple databases requiring incompatible dependency versions.

A robust approach is:

```text
URLClassLoader
      ↓
load driver class
      ↓
instantiate java.sql.Driver
      ↓
driver.connect(url, properties)
```

instead of relying exclusively on globally registered `DriverManager` drivers.

Driver/classloader lifecycle must be managed to avoid leaking old class loaders when drivers are reloaded.

---

# 12. Connection manager

Create a runtime layer distinct from persisted configuration:

```text
DatabaseConnectionManager
```

Responsibilities:

```text
connect()
disconnect()
reconnect()
testConnection()

getConnectionStatus()
getConnection()

commit()
rollback()

setAutoCommit()
```

Runtime state:

```text
DISCONNECTED
CONNECTING
CONNECTED
ERROR
```

Each connected data source should maintain its own JDBC session state.

Do not open JDBC connections merely because Kode started.

Connections are lazy.

---

# 13. Test Connection

The data-source dialog must contain:

```text
Test Connection
```

Successful test:

```text
Connection successful

Database: PostgreSQL 18.x
Driver: PostgreSQL JDBC 42.x
Server: localhost:5432
Latency: 18 ms
```

Failure:

```text
Connection failed

SQLState: 08001
Vendor code: ...
Message: Connection refused
```

Never dump password contents into logs or error messages.

---

# 14. Metadata/introspection architecture

Do not hard-code PostgreSQL/Oracle/H2 metadata into the UI.

Create a generic introspection abstraction:

```text
DatabaseIntrospector
```

with:

```text
JdbcDatabaseIntrospector
```

using standard:

```java
DatabaseMetaData
```

and optional vendor extensions:

```text
PostgreSqlIntrospector
OracleIntrospector
H2Introspector
Db2Introspector
...
```

Architecture:

```text
DatabaseIntrospector
        │
        ├── JDBC generic metadata
        │
        └── VendorMetadataProvider
```

The generic implementation must work for an unknown JDBC driver.

---

# 15. Capability-driven object tree

The object tree must not assume that every database has the same concepts.

Determine available categories from JDBC capabilities and/or the vendor adapter.

Possible categories include:

```text
Databases
Catalogs
Schemas

Tables
Views
Materialized Views

Columns
Constraints
Primary Keys
Foreign Keys
Indexes

Sequences

Types
Domains

Procedures
Functions
Routines
Packages

Triggers

Synonyms
Aliases

Modules

External Tables
External References

Database Links

Users
Roles
```

Only show categories which make sense for the current database.

For example an Oracle tree might look like:

```text
oracle-dev
└─ APP_SCHEMA
   ├─ Tables
   ├─ Views
   ├─ Sequences
   ├─ Types
   ├─ Procedures
   ├─ Functions
   ├─ Packages
   ├─ Synonyms
   └─ Database Links
```

while PostgreSQL could look like:

```text
postgres-dev
└─ kode
   └─ public
      ├─ Tables
      ├─ Views
      ├─ Materialized Views
      ├─ Sequences
      ├─ Functions
      └─ Procedures
```

Do not manufacture empty unsupported categories.

---

# 16. Lazy metadata loading

Metadata exploration must be lazy.

For example:

```text
▶ Tables
```

must not retrieve every table's columns at connection time.

Instead:

```text
connect
    ↓
load root/catalog/schema information

expand Tables
    ↓
load table names

expand customer
    ↓
load columns/constraints/indexes
```

This is essential for large enterprise schemas.

Cache metadata where appropriate.

---

# 17. Refresh semantics

`Refresh` must operate on the selected tree level.

Examples:

Selecting:

```text
Tables
```

and refreshing reloads table metadata.

Selecting:

```text
customer
```

reloads metadata for `customer`.

Selecting:

```text
oracle-dev
```

refreshes database-level metadata.

`Refresh All` invalidates the complete metadata cache associated with that data source.

Refreshing must occur asynchronously.

While loading:

```text
▼ Tables
   Loading...
```

The rest of the UI must remain responsive.

---

# 18. Metadata representation

Do not expose raw `ResultSet`s to the UI.

Create normalized models, for example:

```text
DatabaseObject
DatabaseCatalog
DatabaseSchema
DatabaseTable
DatabaseView
DatabaseColumn
DatabaseRoutine
DatabaseProcedure
DatabaseFunction
DatabasePackage
DatabaseType
DatabaseSequence
DatabaseForeignKey
DatabaseIndex
```

All should at minimum provide something equivalent to:

```text
id
name
qualifiedName
objectType
catalog
schema
attributes
```

Vendor-specific attributes may be retained in:

```text
Map<String, Any?>
```

without forcing the generic UI to understand them.

---

# 19. Object inspection

Activating a database object opens an inspector.

For a table:

```text
customer

Columns
──────────────────────────────────────────
id          BIGINT        NOT NULL    PK
name        VARCHAR(200)  NOT NULL
active      BOOLEAN
created     TIMESTAMP

Indexes
──────────────────────────────────────────
customer_pk       UNIQUE(id)
customer_name_ix  (name)

Foreign Keys
──────────────────────────────────────────
...

DDL
──────────────────────────────────────────
CREATE TABLE ...
```

This may initially be implemented in a read-only content tab.

Reuse Kode's normal content area rather than creating a completely different screen system.

---

# 20. SQL console sessions

A user can create a console from:

```text
Database → New SQL Console
```

or:

```text
connection context menu → New SQL Console
```

The SQL console appears as an ordinary content tab.

Example:

```text
sql@postgres-dev:1
sql@postgres-dev:2
sql@oracle-prod:1
```

The console owns:

```text
sessionId
dataSourceId
catalog
schema
autoCommit state
editor buffer
execution history
```

The console is associated with exactly one datasource at a time.

Optionally allow reassignment through a datasource selector.

---

# 21. SQL editor

Use the existing Kode editor.

The SQL console should behave like a regular editable text document:

```sql
select c.id,
       c.name
from customer c
where c.active = true;
```

It should receive the normal Kode SQL syntax highlighting if available.

Do not implement a completely separate text editor for SQL.

The console may be ephemeral but should offer:

```text
Save As...
```

to save the current buffer as a normal `.sql` file.

---

# 22. Console header/status information

Show the connection associated with the editor somewhere unobtrusive.

For example:

```text
[postgres-dev] [kode] [public] [Auto]
```

or in the global status bar:

```text
DB: postgres-dev ●   kode/public   AUTO
```

This is particularly important when several SQL consoles are open simultaneously.

Executing SQL against the wrong database must be hard to do accidentally.

---

# 23. SQL execution commands

Minimum execution commands:

```text
Execute Statement
Execute Selection
Execute All
Cancel Execution
```

Recommended shortcuts, adapting to existing Kode conventions:

```text
Ctrl+Enter          Execute current statement / selection
Ctrl+Shift+Enter    Execute complete editor
Esc                 Cancel running statement where applicable
```

Actual bindings should respect Kode's existing command/keybinding system.

Do not hard-wire key handling inside database UI classes.

---

# 24. Determining the current statement

Execution semantics:

If text is selected:

```text
execute selection
```

otherwise:

```text
execute SQL statement containing the caret
```

A statement boundary must understand at least:

```text
;
'quoted strings'
"quoted identifiers"
-- line comments
/* block comments */
```

Do not naïvely use:

```text
text.split(";")
```

because semicolons can occur inside SQL strings, procedural bodies, comments, etc.

Design the statement locator as an independent service so dialect-specific parsing can later replace or enhance it.

---

# 25. Query execution pipeline

Conceptually:

```text
SQL editor
   ↓
SqlExecutionRequest
   ↓
SqlExecutionService
   ↓
JDBC Statement
   ↓
SqlExecutionResult
   ↓
Result panel
```

Request:

```text
SqlExecutionRequest {
    dataSourceId
    consoleId
    sql
    catalog
    schema
    executionOptions
}
```

Result:

```text
SqlExecutionResult {
    resultSets
    updateCounts
    warnings
    executionTime
    error
}
```

Do not return JDBC objects directly into the TUI layer.

---

# 26. Bottom result panel

Executing a query automatically opens a bottom panel inside the content area.

Layout:

```text
┌─────────────────────────────────────────────┐
│ SQL editor                                  │
│                                             │
│ SELECT * FROM customer;                     │
│                                             │
├─────────────────────────────────────────────┤
│ Results 1 │ Results 2 │ Messages │ Output   │
├─────┬──────────────┬─────────────┬───────────┤
│ ID  │ NAME         │ ACTIVE      │ CREATED   │
├─────┼──────────────┼─────────────┼───────────┤
│ 1   │ Alice        │ true        │ ...       │
│ 2   │ Bob          │ false       │ ...       │
└─────┴──────────────┴─────────────┴───────────┘
```

The divider between editor and result panel must be resizable if Kode's panel system supports resizing.

The results panel should also support:

```text
hide
show
maximize
restore
```

through existing Kode panel mechanisms if available.

---

# 27. Result table

The result grid requires:

```text
column headers
column JDBC type information
rows
NULL representation
horizontal scrolling
vertical scrolling
column resizing if supported
keyboard cell navigation
copy cell
copy row
copy selected region
copy all
```

NULL must be distinguishable from the string `"NULL"`.

For example render NULL as:

```text
<null>
```

or a themed dimmed:

```text
NULL
```

Internally retain typed values wherever practical.

---

# 28. Large result sets

Never blindly load millions of rows into terminal memory.

Use a configurable result limit.

Default example:

```text
500 rows
```

Configuration:

```text
database.query.maxRows = 500
```

Result status:

```text
500+ rows | limited to 500 | 211 ms
```

Where JDBC supports it, also configure:

```text
Statement.setMaxRows()
Statement.setFetchSize()
```

A future implementation may support paging.

The model must therefore not assume that every result is permanently materialized.

---

# 29. Multiple result sets

JDBC statements can produce:

```text
ResultSet
update count
ResultSet
...
```

Support this correctly.

Represent multiple results as tabs:

```text
Results 1
Results 2
Update 3
Messages
```

Stored procedures in particular may generate several outputs.

---

# 30. Non-query statements

For:

```sql
UPDATE customer SET active = false WHERE id = 12;
```

show:

```text
Statement executed successfully.

Rows affected: 1
Execution time: 17 ms
```

For DDL:

```sql
CREATE TABLE ...
```

show:

```text
Statement executed successfully.
Execution time: 32 ms
```

After successful schema-changing DDL, metadata may be marked stale.

Do not automatically perform a full metadata refresh unless cheap and appropriate.

---

# 31. Errors

SQL errors appear in the lower panel:

```text
Error
─────────────────────────────────────────────
relation "custmer" does not exist

SQLState: 42P01
Vendor code: 0
Position: 15
Execution time: 11 ms
```

Where the database reports a position, attempt to highlight or navigate the editor caret to that position.

Do not allow an exception to destroy the SQL console.

---

# 32. JDBC warnings

Consume and expose:

```java
SQLWarning
```

separately from hard errors.

Show these under:

```text
Messages
```

---

# 33. Cancellation

A running query must not freeze the TUI.

Execution occurs in a background worker.

The console displays:

```text
Running... 12.4 s   [Cancel]
```

Cancellation invokes:

```java
Statement.cancel()
```

where supported.

If JDBC cancellation fails, report that accurately instead of pretending the query stopped.

---

# 34. Transaction handling

Each SQL console should expose:

```text
Auto Commit ON/OFF
Commit
Rollback
```

Default should follow the datasource definition.

Status bar example:

```text
DB: oracle-dev ●    APP_SCHEMA    TX
```

versus:

```text
DB: oracle-dev ●    APP_SCHEMA    AUTO
```

When an editor/session has an uncommitted transaction, make this visible.

Before closing a console with an active transaction:

```text
This SQL console contains an uncommitted transaction.

[Commit] [Rollback] [Cancel]
```

Do not silently commit.

---

# 35. Connection loss

When JDBC reports a dead connection:

```text
postgres-dev × connection lost
```

Actions:

```text
Reconnect
Disconnect
```

Query execution against a disconnected datasource can prompt:

```text
postgres-dev is disconnected.

[Connect and Execute] [Cancel]
```

Do not silently reconnect and execute destructive SQL without clear user action.

---

# 36. Data-source Properties

Existing definitions can be edited.

Form:

```text
┌──────────── Data Source Properties ────────────────┐
│ Name:       [ oracle-dev                         ] │
│ Driver:     [ Oracle JDBC                     ▼ ] │
│ JDBC URL:   [ jdbc:oracle:thin:@...             ] │
│ User:       [ APP                               ] │
│ Password:   [ ********                          ] │
│                                                   │
│ Properties                                        │
│ ┌─────────────────┬─────────────────────────────┐ │
│ │ oracle.net...   │ value                       │ │
│ └─────────────────┴─────────────────────────────┘ │
│                                                   │
│ [Driver...]             [Test] [Apply] [Cancel]   │
└───────────────────────────────────────────────────┘
```

Changing connection-critical properties should invalidate the current live connection.

Prompt before disconnecting if transactions are active.

---

# 37. JDBC properties editor

Provide generic arbitrary properties:

```text
Property                     Value
────────────────────────────────────────
ssl                          true
connectTimeout               10
applicationName              Kode
```

Operations:

```text
Add
Remove
Edit
```

Pass them via a `Properties` object during JDBC connection creation.

This is important because JDBC vendors often expose features that cannot reasonably receive dedicated UI controls.

---

# 38. Driver management UI

Provide:

```text
Database → Drivers...
```

Conceptual view:

```text
Drivers
────────────────────────────────────────────────────────
PostgreSQL JDBC
  Version: 42.7.x
  State: Installed
  Class: org.postgresql.Driver
  JAR: plugins/jdbc/postgresql/...

Oracle JDBC
  Version: 23.x
  State: Installed

DB2 JDBC
  State: Not installed

[Install] [Update] [Remove] [Add custom driver]
```

Removing a driver currently used by a datasource should require confirmation.

---

# 39. Driver discovery

When opening an existing data-source configuration:

```text
driver not installed
```

should not make the datasource disappear.

Display:

```text
oracle-production
    Driver missing
```

and allow:

```text
Install Driver
Choose JAR
Edit Connection
Remove Connection
```

---

# 40. Suggested service interfaces

Names may be adjusted to repository conventions.

```kotlin
interface DatabaseService {
    fun dataSources(): List<DataSourceDefinition>

    suspend fun connect(id: DataSourceId)
    suspend fun disconnect(id: DataSourceId)

    suspend fun testConnection(definition: DataSourceDefinition): ConnectionTestResult

    suspend fun introspect(
        id: DataSourceId,
        request: MetadataRequest
    ): MetadataResult

    suspend fun execute(
        request: SqlExecutionRequest
    ): SqlExecutionResult
}
```

Supporting services:

```text
DataSourceRepository
CredentialStore
JdbcDriverRegistry
JdbcDriverResolver
JdbcDriverLoader

DatabaseConnectionManager
DatabaseMetadataService
DatabaseQueryService

SqlStatementLocator
SqlConsoleManager
```

Keep these services usable without TUI classes.

---

# 41. Internal package separation

Adapt names to the actual Kode source tree, but aim for approximately:

```text
database/
    model/

    config/
        DataSourceRepository
        CredentialStore

    driver/
        JdbcDriverDefinition
        JdbcDriverRegistry
        JdbcDriverResolver
        JdbcDriverDownloader
        JdbcDriverLoader

    connection/
        DatabaseConnection
        DatabaseConnectionManager

    metadata/
        DatabaseIntrospector
        JdbcDatabaseIntrospector
        MetadataCache
        vendor/

    query/
        SqlExecutionService
        SqlExecutionRequest
        SqlExecutionResult
        SqlStatementLocator

    console/
        SqlConsole
        SqlConsoleManager

    ui/
        DatabasePanel
        DataSourceDialog
        DriverDialog
        MetadataTree
        ResultPanel
        ResultTable
```

Avoid creating one giant `DatabaseManager` containing all responsibilities.

---

# 42. Threading

No JDBC network operation may execute on Kode's rendering/input thread.

This includes:

```text
connecting
test connection
driver download
metadata introspection
query execution
commit/rollback
disconnect when blocking
```

Flow:

```text
TUI action
     ↓
background task
     ↓
service result/event
     ↓
UI state update
     ↓
TUI redraw
```

The UI should visibly indicate operation state.

---

# 43. Events/state changes

Prefer explicit state/events instead of direct cross-component manipulation.

Useful events include:

```text
DataSourceAdded
DataSourceUpdated
DataSourceRemoved

ConnectionStateChanged

MetadataInvalidated
MetadataLoaded

SqlExecutionStarted
SqlExecutionCompleted
SqlExecutionFailed
SqlExecutionCancelled
```

Use Kode's existing event/state architecture if one exists.

---

# 44. Database panel tree model

Tree nodes should be model objects such as:

```text
DataSourceNode
CatalogNode
SchemaNode
CategoryNode
DatabaseObjectNode
LoadingNode
ErrorNode
```

Example:

```text
DataSourceNode(postgres-dev)
 └── CatalogNode(kode)
      └── SchemaNode(public)
           ├── CategoryNode(TABLE)
           │    ├── DatabaseObjectNode(customer)
           │    └── DatabaseObjectNode(invoice)
           └── CategoryNode(VIEW)
                └── DatabaseObjectNode(open_invoice)
```

Do not encode object hierarchy into display strings.

---

# 45. Metadata cache

Maintain a cache associated with the live datasource:

```text
MetadataCache
```

Entries should be invalidatable individually:

```text
invalidate datasource
invalidate schema
invalidate category
invalidate object
```

Disconnecting may retain metadata for browsing if desired, but visually identify it as cached/stale.

A simple v1 may clear metadata on disconnect.

---

# 46. SQL console persistence

SQL console state should survive accidental UI navigation during the Kode session.

Optional session restoration after Kode restart may use:

```text
database/state.json
```

Do not persist millions of result rows.

Persist only things such as:

```text
console id
datasource id
SQL buffer
current schema
caret position
```

Result sets are transient.

---

# 47. SQL files versus SQL consoles

Keep the distinction clear.

Normal file:

```text
queries/report.sql
```

is a file.

Database console:

```text
sql@oracle-dev:2
```

is a database session.

A normal `.sql` file may eventually be attachable to a datasource, but do not make every SQL file automatically execute against some database.

---

# 48. Responsive TUI layout

Kode must continue working on smaller terminal widths.

Large:

```text
┌────────────┬─────────────────────────────────────────┐
│ Database   │ Editor                                  │
│            │                                         │
│            ├─────────────────────────────────────────┤
│            │ Results                                 │
└────────────┴─────────────────────────────────────────┘
```

Medium:

```text
┌──────────┬───────────────────────────────┐
│ Database │ Editor                        │
│          ├───────────────────────────────┤
│          │ Results                       │
└──────────┴───────────────────────────────┘
```

Small terminals may temporarily hide the database explorer:

```text
┌─────────────────────────────────────────┐
│ Editor                                  │
├─────────────────────────────────────────┤
│ Results                                 │
└─────────────────────────────────────────┘
```

Do not permit metadata names to force the content area off screen.

Truncate with existing Kode conventions:

```text
very_long_database_object_na…
```

---

# 49. Keyboard navigation

Every operation must remain usable without a mouse.

Database tree should support Kode's normal tree controls, likely conceptually:

```text
Up/Down          node navigation
Left             collapse / parent
Right            expand
Enter            default action
Context-key      actions
```

Database forms:

```text
Tab              next control
Shift+Tab        previous
Enter            activate
Esc              cancel
```

Result table:

```text
arrows           navigate
PgUp/PgDn        page
Home/End         row/column navigation
```

Use Kode's actual key-binding infrastructure.

---

# 50. Mouse interaction

Where mouse handling already exists:

```text
single click       select
double click       expand/open
right click        context menu
drag divider       resize results/editor
wheel              scroll
```

Do not make database functionality dependent on mouse support.

---

# 51. Initial supported generic JDBC features

The generic JDBC implementation should initially support any conforming JDBC driver for:

```text
connection
catalog enumeration
schema enumeration
table enumeration
view enumeration
column enumeration
primary keys
foreign keys
indexes
procedure enumeration
function enumeration
type information
SQL execution
result sets
update counts
transactions
```

Use `DatabaseMetaData` whenever possible.

---

# 52. Vendor-specific metadata

Some concepts cannot be represented well by generic JDBC metadata:

```text
Oracle packages
Oracle database links
PostgreSQL extensions
vendor-specific modules
materialized views
synonyms
external tables
specialized types
```

Introduce extension points rather than adding vendor tests throughout the code.

For example:

```kotlin
interface VendorMetadataProvider {
    fun supports(database: DatabaseIdentity): Boolean

    suspend fun capabilities(...): DatabaseCapabilities

    suspend fun children(...): List<DatabaseObject>
}
```

Then:

```text
Generic JDBC provider
        +
Oracle provider
        +
PostgreSQL provider
```

can contribute metadata.

---

# 53. Database capabilities

Represent capabilities explicitly.

For example:

```text
DatabaseCapabilities {
    catalogs
    schemas
    tables
    views
    materializedViews
    sequences
    procedures
    functions
    packages
    types
    triggers
    synonyms
    externalReferences
    modules
    transactions
    savepoints
}
```

The UI should derive available categories/actions from this model.

Do not scatter checks such as:

```kotlin
if (databaseName == "Oracle")
```

through UI code.

---

# 54. Inspect SQL/DDL

Where JDBC does not expose an object's source/DDL directly, vendor providers may supply it.

The generic implementation should not fabricate DDL from incomplete metadata and present it as authoritative.

It is acceptable initially to show:

```text
DDL unavailable for this driver.
```

---

# 55. Table data shortcut

A useful object action:

```text
Select Rows
```

generates a query into a SQL console rather than implementing a separate table-data editor.

For example:

```sql
SELECT *
FROM public.customer;
```

This keeps the first implementation focused.

Full spreadsheet-style table editing is explicitly **not required for this task**. Data modification remains possible through SQL.

---

# 56. Security requirements

Never print passwords into:

```text
logs
debug output
exception messages
status bar
connection tree
```

Sanitize JDBC URLs where vendors allow credentials embedded in URLs.

For example avoid logging:

```text
jdbc:foo://host/db?user=x&password=secret
```

Connection config should preferably keep password separate from the URL.

---

# 57. Logging

Useful debug logging:

```text
driver installed
driver loaded
connection started
connection established
connection closed
metadata query started/completed
SQL execution started/completed
```

Do not log complete SQL result contents by default.

SQL text may also contain sensitive information, so avoid dumping complete SQL statements at normal log levels.

---

# 58. SQL history

Maintain per-console execution history.

Potential UI command:

```text
Database → Query History
```

This does not need a sophisticated history browser in the initial implementation, but architect execution records so it can be added.

Record:

```text
timestamp
datasourceId
SQL
duration
success/failure
```

Do not persist history unless the existing Kode history system supports that sensibly.

---

# 59. Result export

Design `ResultTableModel` so later functionality can export:

```text
CSV
TSV
JSON
```

An initial implementation may provide:

```text
Copy All
```

without implementing file export yet.

---

# 60. First implementation scope

Implement the feature in coherent vertical slices.

### Slice 1 — infrastructure

Implement:

```text
datasource models
datasource persistence
driver registry
driver loading
connection manager
test connection
```

No database browser yet.

### Slice 2 — explorer

Implement:

```text
Database panel
datasource tree
connect/disconnect
generic JDBC metadata
lazy metadata loading
refresh
```

### Slice 3 — SQL console

Implement:

```text
New SQL Console
SQL editor integration
current statement detection
background execution
errors
update counts
```

### Slice 4 — results

Implement:

```text
bottom result panel
result tabs
table grid
scrolling
copy
row limits
```

### Slice 5 — advanced JDBC handling

Implement:

```text
transactions
query cancellation
warnings
multiple result sets
connection-loss handling
```

### Slice 6 — vendor extensions

Implement architecture and initially add vendor support only where useful.

Do not delay generic JDBC functionality until dozens of database-specific adapters exist.

---

# 61. Suggested initial driver catalog

Ship driver **descriptions**, not necessarily the actual drivers.

Start with:

```text
H2
HSQLDB
SQLite

PostgreSQL
MySQL
MariaDB

Oracle
Microsoft SQL Server
IBM DB2

Apache Derby

Generic JDBC
```

Other databases can be added through driver definitions without structural changes.

---

# 62. Example user workflow

The following workflow must work end-to-end.

User opens:

```text
Database → New Data Source
```

and selects:

```text
H2
```

Kode sees that H2 JDBC is absent.

It offers:

```text
Download H2 JDBC driver?
```

User confirms.

Kode stores the driver under its plugins area.

User enters:

```text
Name:
local-h2

URL:
jdbc:h2:tcp://localhost:9092/default

User:
sa

Password:
...
```

User executes:

```text
Test Connection
```

Kode reports success.

User saves.

The left panel shows:

```text
▶ local-h2 ○
```

User activates Connect:

```text
▼ local-h2 ●
    ▶ PUBLIC
```

Expanding `PUBLIC` results in:

```text
▼ PUBLIC
    ▶ Tables
    ▶ Views
    ▶ Routines
```

Expanding tables:

```text
▼ Tables
    CUSTOMER
    ORDER
    PRODUCT
```

User selects:

```text
New SQL Console
```

The content area opens:

```text
sql@local-h2:1
```

User writes:

```sql
select 1 as something
union all
select 2
union all
select 3;
```

and executes.

Kode opens the result panel:

```text
SOMETHING
─────────
1
2
3
```

with:

```text
3 rows | 7 ms
```

This exact basic journey is an acceptance requirement.

---

# 63. Acceptance criteria

The feature is not complete until all of the following work:

1. A data source can be created and persisted.
2. Kode can reopen and display the persisted data source after restart.
3. JDBC drivers can be loaded dynamically without inclusion in the Kode application classpath.
4. A missing configured driver can be installed into the plugin area.
5. A user can test a connection.
6. A user can connect and disconnect.
7. The database panel reflects connection state.
8. Database metadata can be explored.
9. Metadata is lazy-loaded rather than loading the entire database at connect time.
10. The selected metadata branch can be refreshed.
11. A SQL console can be created for a connection.
12. SQL is edited using Kode's normal editor.
13. Selection/current-statement execution works.
14. JDBC execution does not block TUI rendering/input.
15. SELECT results appear in a lower content panel.
16. DML update counts appear correctly.
17. SQL errors are displayed without destroying the editor/session.
18. Multiple result sets are representable.
19. Result size is bounded.
20. Transactions support commit and rollback when autocommit is disabled.
21. Passwords are not printed or persisted inside project files.
22. The database feature works entirely with keyboard navigation.
23. Generic JDBC databases function without a dedicated vendor adapter.

---

# 64. Testing requirements

Add unit tests around the non-UI services.

Especially:

```text
DataSourceRepository
DriverRegistry
driver resolution
SqlStatementLocator
MetadataCache
DatabaseCapabilities
result conversion
```

Use H2 and/or HSQLDB for JDBC integration tests because they can run locally without an external database.

Integration test example:

```text
create H2 datasource
connect
create table
insert rows
introspect schema
verify table discovered
execute select
verify result columns
verify rows
disconnect
```

Test statement detection with:

```sql
select ';';

select 1; -- comment ;

select 'abc;def';

/*
 ;
*/
select 2;
```

Also test:

```text
connection failure
invalid driver
missing driver
invalid SQL
zero-row result
NULL values
multiple JDBC types
DDL
DML
rollback
```

---

# 65. Important implementation constraints for Codex

Before coding:

1. Inspect the existing Kode architecture.
2. Locate the implementations of:

   * left-side panels;
   * content tabs;
   * editor buffers;
   * bottom panels;
   * tree widgets;
   * dialogs/forms;
   * popup/context menus;
   * menu commands;
   * keybindings;
   * asynchronous/background jobs;
   * configuration directories;
   * plugin directories;
   * persistence;
   * status bar;
   * theming.
3. Reuse those abstractions.
4. Do not introduce another UI framework.
5. Do not redesign existing Kode unrelated to this feature.
6. Keep JDBC/domain code independent of terminal rendering.
7. Build and run the existing tests after each major slice.
8. Add focused tests for the database feature.
9. Keep source changes incremental and reviewable.

Because Kode is a JVM application, use the native JDBC APIs directly; a separate database framework/ORM is unnecessary and would interfere with the goal of supporting arbitrary JDBC drivers dynamically.

---

# 66. Target UX summary

The finished interaction should conceptually feel like:

```text
                    KODE
                     │
        ┌────────────┴─────────────┐
        │                          │
 Database Explorer             Content
        │                          │
        │                    SQL Console
        │                          │
 metadata tree              SQL editor
        │                          │
        │                    Execute SQL
        │                          │
        │                          ▼
        │                    Result Panel
        │                     ├ Results
        │                     ├ Messages
        │                     └ Errors
        │
        ▼
 JDBC service
        │
 Driver abstraction
        │
        ▼
     Database
```

The core architectural rule is:

```text
Database UI ≠ JDBC implementation
```

The TUI displays and manipulates normalized Kode database models. JDBC is an interchangeable backend beneath those models.

This keeps the feature compatible with the existing Kode architecture, generic JDBC databases, future vendor-specific metadata adapters, and potentially even non-JDBC database backends later without having to redesign the editor or database explorer.

