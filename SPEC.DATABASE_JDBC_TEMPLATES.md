Implement JDBC URL suggestions in the Kode Database Manager create/edit
data-source dialog.

CONTEXT

The Database Manager already has a catalog of JDBC database types/drivers and
a create/edit data-source dialog containing a JDBC URL field.

Enhance that JDBC URL field into an editable combo/suggestion control.

The suggestions popup MUST contain two groups, in this exact order:

    Recents
    Standard

"Recents" contains previously used JDBC URLs compatible with the currently
selected database type / JDBC protocol family.

"Standard" contains curated, morphologically valid JDBC URL templates for the
currently selected JDBC driver.

The user can:
- continue typing an arbitrary URL;
- select a recent URL;
- select a standard template;
- edit the selected value afterwards.

A suggestion NEVER restricts or validates what may ultimately be entered.
Driver.acceptsURL(), Test Connection, and the JDBC driver itself remain the
authoritative validators.


========================================================================
1. UI BEHAVIOUR
========================================================================

For the JDBC URL field:

    JDBC URL:
    [ jdbc:postgresql://localhost:5432/mydb                  ▼ ]

Opening the popup should conceptually render:

    Recents
      jdbc:postgresql://db1.internal:5432/app
      jdbc:postgresql://localhost:5432/test

    Standard
      TCP / host + port + database
          jdbc:postgresql://{host}:5432/{database}

      TCP / default port
          jdbc:postgresql://{host}/{database}

      Local defaults
          jdbc:postgresql:{database}

      Multi-host failover
          jdbc:postgresql://{host1}:5432,{host2}:5432/{database}

The labels/descriptions are UI metadata. Selecting an entry inserts only its
template/value into the editable JDBC URL field.

The `{foo}` parts are literal editable placeholders. Do NOT implement a
separate placeholder wizard as part of this task.

Changing the selected database type/driver must immediately update the
Standard group.

Changing the driver must NOT silently replace a JDBC URL which the user has
already typed. Only selection of a suggestion changes the field value.

Hide a group when it is empty.

If both groups are empty, the JDBC URL remains a completely normal editable
text field.


========================================================================
2. KEYBOARD / TUI BEHAVIOUR
========================================================================

Integrate with the existing Kode TUI controls and keybinding/focus system.

Required behaviour:

- focus JDBC URL field normally;
- invoke suggestions using the normal combo/dropdown action;
- Up/Down moves through selectable suggestions;
- group headers are not selectable;
- Enter selects;
- Esc closes without modifying the value;
- typing/editing remains possible;
- long URLs scroll horizontally according to the existing text-field
  behaviour;
- mouse support may also select entries where supported by the existing TUI.

Do not introduce a separate UI toolkit.


========================================================================
3. RESOURCE-DRIVEN DESIGN
========================================================================

Do NOT hard-code JDBC URL templates in dialog code.

Add a resource alongside the existing JDBC driver catalog, e.g.

    database-jdbc-url-templates.json

or adapt the exact path/name/serialization to the existing resource conventions
in the repository.

The resource model should provide approximately:

JdbcUrlTemplateCatalog
    version
    drivers[]

JdbcUrlDriverTemplates
    driverId
    recentFamily
    defaultPort?
    inherit?
    templates[]
    note?

JdbcUrlTemplate
    id
    label
    template
    description?
    advanced = false
    legacy = false
    minDriverVersion?
    note?

Use the existing built-in JDBC driver IDs EXACTLY.

`inherit` means inherit templates from another driver family.

`recentFamily` determines which drivers share MRU history. For example:

    postgresql
    cockroachdb
    greenplum
    yugabytedb
    timescaledb
    citus
    aurora-postgresql

all use PostgreSQL JDBC morphology and should use:

    recentFamily = postgresql

Likewise MySQL-compatible aliases share `mysql`, Spark Thrift shares `hive`,
etc.

Aliases may inherit all standard templates and optionally prepend additional
templates that are more appropriate for that product.


========================================================================
4. RECENTS
========================================================================

Maintain MRU JDBC URLs independently of the Standard resource.

Persist them in Kode's existing LOCAL parameters/configuration location.
Never place them in the project/workspace.

Do not hard-code $HOME or an operating-system-specific location.

Recommended logical model:

    jdbcUrlRecents:
        postgresql:
            - ...
        oracle:
            - ...
        h2:
            - ...

Maximum:

    12 URLs per recentFamily

Ordering:

    most recently used first

Deduplication:

    exact string after leading/trailing whitespace is removed

Do NOT lowercase, canonicalize, reorder parameters, decode URL escapes, or
otherwise modify a JDBC URL. JDBC URL contents may be case-sensitive and
vendor-specific.

Add a URL to Recents when a non-empty URL is successfully used by any of:

- Save Data Source
- Test Connection
- Connect

It is sufficient to add on successful Save/Test/Connect. Failed attempts should
not become recents.

For migration, it is acceptable and useful to seed the MRU set once from
already persisted data-source definitions.


========================================================================
5. RECENT-URL SECURITY
========================================================================

Never persist a recent JDBC URL which appears to contain credentials or
secrets.

Prefer refusing to remember it rather than creating a redacted URL which may
look executable but no longer is.

Detect sensitive parameter names case-insensitively, including at least:

    password
    passwd
    pwd
    pass
    secret
    clientSecret
    OAuthClientSecret
    token
    accessToken
    OAuthAccessToken
    OAuthRefreshToken
    apiKey
    api_key
    privateKey
    SecretAccessKey
    AccessKeyID
    sessionToken
    credentials

Also reject obvious URL user-info credentials such as:

    protocol://user:password@host

including their equivalent after a `jdbc:` prefix.

The standard template catalog below deliberately contains NO username,
password, API key, token, private key, access key, or client secret.

The Database Manager already has dedicated username/password/property fields;
keep credentials there.


========================================================================
6. STANDARD TEMPLATE CATALOG
========================================================================

Implement the following exact built-in dataset.

The notation below is specification notation; serialize it using the project's
actual resource-file conventions.

----------------------------------------------------------------------
POSTGRESQL
----------------------------------------------------------------------

driverId: postgresql
recentFamily: postgresql
defaultPort: 5432

templates:

basic
label: TCP / host + port + database
template:
    jdbc:postgresql://{host}:5432/{database}

default-port
label: TCP / default port
template:
    jdbc:postgresql://{host}/{database}

local
label: Local/default host
template:
    jdbc:postgresql:{database}
note:
    Uses the driver's default host and port.

failover
label: Multiple hosts / failover
template:
    jdbc:postgresql://{host1}:5432,{host2}:5432/{database}

primary
label: Multiple hosts / require primary
template:
    jdbc:postgresql://{host1}:5432,{host2}:5432/{database}?targetServerType=primary

read-preferred
label: Multiple hosts / prefer secondary + load balance
template:
    jdbc:postgresql://{host1}:5432,{host2}:5432/{database}?targetServerType=preferSecondary&loadBalanceHosts=true

tls-verify-full
label: TLS / verify server identity
template:
    jdbc:postgresql://{host}:5432/{database}?sslmode=verify-full


----------------------------------------------------------------------
MYSQL
----------------------------------------------------------------------

driverId: mysql
recentFamily: mysql
defaultPort: 3306

templates:

basic
label: TCP
template:
    jdbc:mysql://{host}:3306/{database}

default-port
label: TCP / default port
template:
    jdbc:mysql://{host}/{database}

multi-host
label: Multiple hosts / failover
template:
    jdbc:mysql://{host1}:3306,{host2}:3306/{database}

loadbalance
label: Load balancing
template:
    jdbc:mysql:loadbalance://{host1}:3306,{host2}:3306/{database}

replication
label: Source / replica
template:
    jdbc:mysql:replication://{source}:3306,{replica}:3306/{database}

srv
label: DNS SRV
template:
    jdbc:mysql+srv://{dnsSrvService}/{database}
note:
    DNS SRV form must not specify a port.

srv-loadbalance
label: DNS SRV / load balancing
template:
    jdbc:mysql+srv:loadbalance://{dnsSrvService}/{database}

srv-replication
label: DNS SRV / replication
template:
    jdbc:mysql+srv:replication://{dnsSrvService}/{database}

host-properties
label: Host property syntax
template:
    jdbc:mysql://(host={host},port=3306)/{database}
advanced: true

tls
label: TLS required
template:
    jdbc:mysql://{host}:3306/{database}?sslMode=VERIFY_IDENTITY


----------------------------------------------------------------------
MARIADB
----------------------------------------------------------------------

driverId: mariadb
recentFamily: mariadb
defaultPort: 3306

templates:

basic
label: TCP
template:
    jdbc:mariadb://{host}:3306/{database}

default-port
label: TCP / default port
template:
    jdbc:mariadb://{host}/{database}

sequential
label: Sequential failover
template:
    jdbc:mariadb:sequential://{host1}:3306,{host2}:3306/{database}

loadbalance
label: Load balancing
template:
    jdbc:mariadb:loadbalance://{host1}:3306,{host2}:3306/{database}

replication
label: Primary / replica
template:
    jdbc:mariadb:replication://{primary}:3306,{replica}:3306/{database}

load-balance-read
label: Primary failover + replica read balancing
template:
    jdbc:mariadb:load-balance-read://{primary1}:3306,{primary2}:3306,address=(host={replica1})(port=3306)(type=replica)/{database}
minDriverVersion: 3.5.1

local-socket
label: Unix local socket
template:
    jdbc:mariadb://address=(localSocket={socketPath})/{database}
minDriverVersion: 3.4.1
advanced: true

tls
label: TLS / verify server identity
template:
    jdbc:mariadb://{host}:3306/{database}?sslMode=verify-full

IMPORTANT:
Do NOT add the old `jdbc:mariadb:aurora:` mode. MariaDB Connector/J 3.x no
longer supports that old connector mode.


----------------------------------------------------------------------
ORACLE
----------------------------------------------------------------------

driverId: oracle
recentFamily: oracle
defaultPort: 1521

templates:

service
label: Thin / host + service name
template:
    jdbc:oracle:thin:@//{host}:1521/{serviceName}

service-custom-port
label: Thin / custom port + service name
template:
    jdbc:oracle:thin:@//{host}:{port}/{serviceName}

easy-connect-tcp
label: Easy Connect Plus / TCP
template:
    jdbc:oracle:thin:@tcp:{host}:1521/{serviceName}

tcps
label: Easy Connect Plus / TCPS
template:
    jdbc:oracle:thin:@tcps:{host}:1521/{serviceName}

multi-host
label: Easy Connect Plus / multiple hosts
template:
    jdbc:oracle:thin:@tcp:{host1}:1521,{host2}:1521/{serviceName}

multi-host-different-ports
label: Easy Connect Plus / multiple hosts and ports
template:
    jdbc:oracle:thin:@tcp:{host1}:1521,{host2}:1522/{serviceName}

tns-alias
label: TNS alias
template:
    jdbc:oracle:thin:@{tnsAlias}
note:
    Requires Oracle network/TNS configuration which resolves the alias.

sid-legacy
label: Thin / SID
template:
    jdbc:oracle:thin:@{host}:1521:{sid}
legacy: true
note:
    Legacy SID form. Prefer service-name URLs for modern Oracle installations.

descriptor
label: Full DESCRIPTION / service
template:
    jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST={host})(PORT=1521))(CONNECT_DATA=(SERVICE_NAME={serviceName})))
advanced: true

descriptor-ha
label: DESCRIPTION / load-balanced addresses
template:
    jdbc:oracle:thin:@(DESCRIPTION=(LOAD_BALANCE=on)(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST={host1})(PORT=1521))(ADDRESS=(PROTOCOL=TCP)(HOST={host2})(PORT=1521)))(CONNECT_DATA=(SERVICE_NAME={serviceName})))
advanced: true

ldap
label: LDAP naming
template:
    jdbc:oracle:thin:@ldap://{ldapHost}:7777/{serviceName},cn=OracleContext,dc={domain},dc={tld}
advanced: true

ldaps
label: LDAP over TLS
template:
    jdbc:oracle:thin:@ldaps://{ldapHost}:1636/{serviceName},cn=OracleContext,dc={domain},dc={tld}
advanced: true


----------------------------------------------------------------------
MICROSOFT SQL SERVER
----------------------------------------------------------------------

driverId: sqlserver
recentFamily: sqlserver
defaultPort: 1433

templates:

basic
label: TCP / database
template:
    jdbc:sqlserver://{host}:1433;databaseName={database}

custom-port
label: TCP / custom port
template:
    jdbc:sqlserver://{host}:{port};databaseName={database}

named-instance
label: Named instance
template:
    jdbc:sqlserver://{host};instanceName={instance};databaseName={database}

tls
label: TLS / validate server certificate
template:
    jdbc:sqlserver://{host}:1433;databaseName={database};encrypt=true;trustServerCertificate=false

kerberos
label: Java Kerberos
template:
    jdbc:sqlserver://{host}:1433;databaseName={database};integratedSecurity=true;authenticationScheme=JavaKerberos
advanced: true

IMPORTANT:
Do NOT provide templates containing trustServerCertificate=true.


----------------------------------------------------------------------
IBM DB2
----------------------------------------------------------------------

driverId: db2
recentFamily: db2
defaultPort: 50000

templates:

luw
label: Db2 LUW / Type 4
template:
    jdbc:db2://{host}:50000/{database}

custom-port
label: Db2 / custom port
template:
    jdbc:db2://{host}:{port}/{database}

schema
label: Db2 / current schema
template:
    jdbc:db2://{host}:50000/{database}:currentSchema={schema};

ssl
label: Db2 / TLS
template:
    jdbc:db2://{host}:50001/{database}:sslConnection=true;
note:
    50001 is common for SSL-configured LUW installations but is not universal.

zos
label: Db2 for z/OS / location
template:
    jdbc:db2://{host}:446/{location}
advanced: true
note:
    446 is a common DRDA SSL/listener deployment port but must remain editable.


----------------------------------------------------------------------
SQLITE / XERIAL
----------------------------------------------------------------------

driverId: sqlite
recentFamily: sqlite

templates:

relative-file
label: File / relative path
template:
    jdbc:sqlite:{file}.db

absolute-file
label: File / absolute path
template:
    jdbc:sqlite:{absolutePath}

memory
label: Private in-memory database
template:
    jdbc:sqlite::memory:

temporary
label: Temporary database
template:
    jdbc:sqlite:
note:
    Empty SQLite database name creates a temporary database.

resource
label: Classpath/resource database
template:
    jdbc:sqlite::resource:{resourcePath}
advanced: true
note:
    Resource databases are read-only.

IMPORTANT:
Do NOT offer TCP/server URLs for Xerial SQLite. It is an in-process/file
database driver.


----------------------------------------------------------------------
H2
----------------------------------------------------------------------

driverId: h2
recentFamily: h2
defaultPort: 9092

templates:

memory-private
label: Private in-memory database
template:
    jdbc:h2:mem:

memory-named
label: Named in-memory database
template:
    jdbc:h2:mem:{database}

memory-keep-alive
label: Named memory / keep alive
template:
    jdbc:h2:mem:{database};DB_CLOSE_DELAY=-1

file-relative
label: File / relative
template:
    jdbc:h2:file:./{database}

file-home
label: File / user home
template:
    jdbc:h2:~/{database}

file-absolute
label: File / absolute
template:
    jdbc:h2:file:{absolutePath}

tcp
label: H2 TCP server
template:
    jdbc:h2:tcp://{host}:9092/{databasePath}

tcp-home
label: H2 TCP / server user home
template:
    jdbc:h2:tcp://{host}:9092/~/{database}

tcp-memory
label: H2 TCP / server-side in-memory database
template:
    jdbc:h2:tcp://{host}:9092/mem:{database}

tls
label: H2 SSL server
template:
    jdbc:h2:ssl://{host}:9092/{databasePath}

encrypted-file
label: Encrypted file database
template:
    jdbc:h2:file:{absolutePath};CIPHER=AES
advanced: true
note:
    H2 encrypted database passwords have special password semantics.


----------------------------------------------------------------------
HSQLDB
----------------------------------------------------------------------

driverId: hsqldb
recentFamily: hsqldb
defaultPort: 9001

templates:

memory
label: In-memory database
template:
    jdbc:hsqldb:mem:{database}

file
label: File database
template:
    jdbc:hsqldb:file:{path}

resource
label: Resource database
template:
    jdbc:hsqldb:res:{resourcePath}

hsql
label: HSQL server
template:
    jdbc:hsqldb:hsql://{host}:9001/{alias}

hsqls
label: HSQL server / TLS
template:
    jdbc:hsqldb:hsqls://{host}:{port}/{alias}

http
label: HTTP server
template:
    jdbc:hsqldb:http://{host}:{port}/{alias}

https
label: HTTPS server
template:
    jdbc:hsqldb:https://{host}:{port}/{alias}


----------------------------------------------------------------------
APACHE DERBY EMBEDDED
----------------------------------------------------------------------

driverId: derby-embedded
recentFamily: derby
templates:

database
label: Embedded database
template:
    jdbc:derby:{database}

create
label: Embedded database / create
template:
    jdbc:derby:{database};create=true

memory
label: In-memory database / create
template:
    jdbc:derby:memory:{database};create=true

directory
label: Directory database
template:
    jdbc:derby:directory:{path}

classpath
label: Read-only classpath database
template:
    jdbc:derby:classpath:{resourcePath}
advanced: true

jar
label: Read-only database in JAR/ZIP
template:
    jdbc:derby:jar:({archivePath}){databasePath}
advanced: true


----------------------------------------------------------------------
APACHE DERBY NETWORK CLIENT
----------------------------------------------------------------------

driverId: derby-client
recentFamily: derby-client
defaultPort: 1527

templates:

network
label: Network Server
template:
    jdbc:derby://{host}:1527/{database}

network-custom
label: Network Server / custom port
template:
    jdbc:derby://{host}:{port}/{database}

create
label: Network Server / create database
template:
    jdbc:derby://{host}:1527/{database};create=true

memory
label: Network Server / in-memory database
template:
    jdbc:derby://{host}:1527/memory:{database};create=true


----------------------------------------------------------------------
FIREBIRD / JAYBIRD
----------------------------------------------------------------------

driverId: firebird
recentFamily: firebird
defaultPort: 3050

templates:

network
label: Pure Java / TCP
template:
    jdbc:firebird://{host}:3050/{database}

default-port
label: Pure Java / default port
template:
    jdbc:firebird://{host}/{database}

localhost
label: Pure Java / localhost
template:
    jdbc:firebird:///{database}

compatibility-prefix
label: firebirdsql compatibility prefix
template:
    jdbc:firebirdsql://{host}:3050/{database}
advanced: true

Do not make Jaybird's ambiguous historical legacy coordinate syntax the default.


----------------------------------------------------------------------
IBM INFORMIX
----------------------------------------------------------------------

driverId: informix
recentFamily: informix

templates:

basic
label: Informix server
template:
    jdbc:informix-sqli://{host}:{port}/{database}:INFORMIXSERVER={serverName}

locale
label: Informix server / locales
template:
    jdbc:informix-sqli://{host}:{port}/{database}:INFORMIXSERVER={serverName};DB_LOCALE={dbLocale};CLIENT_LOCALE={clientLocale}
advanced: true

sqlhosts
label: sqlhosts file
template:
    jdbc:informix-sqli:informixserver={serverName};SQLH_TYPE=FILE;SQLH_FILE={sqlhostsPath}
advanced: true

There is no universal Informix listener port, therefore use {port}.


----------------------------------------------------------------------
INTERSYSTEMS IRIS
----------------------------------------------------------------------

driverId: iris
recentFamily: iris
defaultPort: 1972

templates:

basic
label: IRIS namespace
template:
    jdbc:IRIS://{host}:1972/{namespace}

custom-port
label: IRIS namespace / custom port
template:
    jdbc:IRIS://{host}:{port}/{namespace}


----------------------------------------------------------------------
TIBERO
----------------------------------------------------------------------

driverId: tibero
recentFamily: tibero
defaultPort: 8629

templates:

basic
label: Tibero Thin
template:
    jdbc:tibero:thin:@{host}:8629:{databaseName}

custom-port
label: Tibero Thin / custom port
template:
    jdbc:tibero:thin:@{host}:{port}:{databaseName}

tac
label: TAC / load balancing
template:
    jdbc:tibero:thin:@(description=(load_balance=on)(address_list=(address=(host={host1})(port=8629))(address=(host={host2})(port=8629)))(DATABASE_NAME={databaseName}))
advanced: true

tac-failover
label: TAC / failover + load balancing
template:
    jdbc:tibero:thin:@(description=(failover=on)(load_balance=on)(address_list=(address=(host={host1})(port=8629))(address=(host={host2})(port=8629)))(DATABASE_NAME={databaseName}))
advanced: true


----------------------------------------------------------------------
TERADATA
----------------------------------------------------------------------

driverId: teradata
recentFamily: teradata

templates:

basic
label: Teradata / host
template:
    jdbc:teradata://{host}

database
label: Default database
template:
    jdbc:teradata://{host}/DATABASE={database}

database-port
label: Database + DBS port
template:
    jdbc:teradata://{host}/DATABASE={database},DBS_PORT={port}

encrypted
label: Database + encrypted traffic
template:
    jdbc:teradata://{host}/DATABASE={database},ENCRYPTDATA=ON
advanced: true

browser-sso
label: Browser SSO
template:
    jdbc:teradata://{host}/LOGMECH=BROWSER,BROWSER_TAB_TIMEOUT=0
advanced: true

Teradata parameter grammar is:

    jdbc:teradata://{host}/PARAM=value,PARAM=value,...

Do NOT put USER or PASSWORD into Standard templates.


----------------------------------------------------------------------
VERTICA
----------------------------------------------------------------------

driverId: vertica
recentFamily: vertica
defaultPort: 5433

templates:

basic
label: Vertica
template:
    jdbc:vertica://{host}:5433/{database}

custom-port
label: Vertica / custom port
template:
    jdbc:vertica://{host}:{port}/{database}

server-only
label: Server / database omitted
template:
    jdbc:vertica://{host}:5433

tls
label: TLS / verify server
template:
    jdbc:vertica://{host}:5433/{database}?TLSmode=verify-full

backup-nodes
label: Backup server nodes
template:
    jdbc:vertica://{host}:5433/{database}?BackupServerNode={host2}:5433,{host3}:5433
advanced: true


----------------------------------------------------------------------
EXASOL
----------------------------------------------------------------------

driverId: exasol
recentFamily: exasol
defaultPort: 8563

templates:

basic
label: Exasol
template:
    jdbc:exa:{host}:8563

custom-port
label: Exasol / custom port
template:
    jdbc:exa:{host}:{port}

multiple-hosts
label: Multiple hosts
template:
    jdbc:exa:{host1},{host2}:8563

fingerprint-host
label: TLS certificate fingerprint
template:
    jdbc:exa:{host}/{fingerprint}:8563

fingerprint-property
label: TLS certificate fingerprint property
template:
    jdbc:exa:{host}:8563;fingerprint={fingerprint};
advanced: true

IMPORTANT:
Do NOT add NOCERTCHECK or validateservercertificate=0 suggestions.


----------------------------------------------------------------------
SAP HANA
----------------------------------------------------------------------

driverId: sap-hana
recentFamily: sap-hana

templates:

basic
label: SAP HANA
template:
    jdbc:sap://{host}:{port}

database
label: SAP HANA / database
template:
    jdbc:sap://{host}:{port}/?databaseName={database}

tls
label: SAP HANA / TLS + certificate validation
template:
    jdbc:sap://{host}:{port}/?databaseName={database}&encrypt=true&validateCertificate=true

hana-cloud
label: SAP HANA Cloud
template:
    jdbc:sap://{instance}.{domain}.hanacloud.ondemand.com:443?encrypt=true
advanced: true

Do not assign a universal on-premise HANA port; the port depends on the HANA
deployment/instance/tenant.


----------------------------------------------------------------------
SNOWFLAKE
----------------------------------------------------------------------

driverId: snowflake
recentFamily: snowflake

templates:

basic
label: Snowflake account
template:
    jdbc:snowflake://{accountIdentifier}.snowflakecomputing.com/

context
label: Warehouse + database + schema
template:
    jdbc:snowflake://{accountIdentifier}.snowflakecomputing.com/?warehouse={warehouse}&db={database}&schema={schema}

context-role
label: Warehouse + database + schema + role
template:
    jdbc:snowflake://{accountIdentifier}.snowflakecomputing.com/?warehouse={warehouse}&db={database}&schema={schema}&role={role}

Do not include user/password in these templates.


----------------------------------------------------------------------
AMAZON REDSHIFT
----------------------------------------------------------------------

driverId: redshift
recentFamily: redshift
defaultPort: 5439

templates:

basic
label: Redshift endpoint
template:
    jdbc:redshift://{endpoint}:5439/{database}

custom-port
label: Redshift endpoint / custom port
template:
    jdbc:redshift://{endpoint}:{port}/{database}

ssl
label: Redshift / SSL
template:
    jdbc:redshift://{endpoint}:5439/{database};ssl=true

iam-endpoint
label: IAM / endpoint
template:
    jdbc:redshift:iam://{endpoint}:5439/{database}

iam-cluster
label: IAM / cluster ID + region
template:
    jdbc:redshift:iam://{clusterId}:{region}/{database}

serverless-iam
label: Redshift Serverless / IAM
template:
    jdbc:redshift:iam://{workgroup}.{accountId}.{region}.redshift-serverless.amazonaws.com:5439/{database}
advanced: true

Do not provide AccessKeyID, SecretAccessKey, PWD, token, or client-secret
examples in Standard.


----------------------------------------------------------------------
GOOGLE BIGQUERY JDBC
----------------------------------------------------------------------

driverId: bigquery
recentFamily: bigquery
defaultPort: 443

templates:

adc
label: Application Default Credentials
template:
    jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId={projectId};OAuthType=3;

adc-dataset
label: ADC + default dataset
template:
    jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId={projectId};OAuthType=3;DefaultDataset={dataset};

service-account-file
label: Service-account key file
template:
    jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId={projectId};OAuthType=0;OAuthPvtKeyPath={serviceAccountJsonPath};
advanced: true

A filesystem path to a credential file may be suggested, but never embed the
contents of the key, access token, refresh token, or OAuth client secret.


----------------------------------------------------------------------
TRINO
----------------------------------------------------------------------

driverId: trino
recentFamily: trino

templates:

server
label: Trino server
template:
    jdbc:trino://{host}:8080

catalog
label: Trino / catalog
template:
    jdbc:trino://{host}:8080/{catalog}

schema
label: Trino / catalog + schema
template:
    jdbc:trino://{host}:8080/{catalog}/{schema}

tls
label: Trino / TLS
template:
    jdbc:trino://{host}:443/{catalog}/{schema}?SSL=true
note:
    Port 443 is the driver's default HTTPS port when SSL=true.

Do not provide SSLVerification=NONE as a standard suggestion.


----------------------------------------------------------------------
PRESTODB
----------------------------------------------------------------------

driverId: presto
recentFamily: presto

templates:

server
label: Presto server
template:
    jdbc:presto://{host}:8080

catalog
label: Presto / catalog
template:
    jdbc:presto://{host}:8080/{catalog}

schema
label: Presto / catalog + schema
template:
    jdbc:presto://{host}:8080/{catalog}/{schema}


----------------------------------------------------------------------
APACHE HIVE
----------------------------------------------------------------------

driverId: hive
recentFamily: hive
defaultPort: 10000

templates:

binary
label: HiveServer2 / binary
template:
    jdbc:hive2://{host}:10000/{database}

embedded
label: Embedded Hive
template:
    jdbc:hive2:///

http
label: HiveServer2 / HTTP transport
template:
    jdbc:hive2://{host}:10001/{database};transportMode=http;httpPath=cliservice
advanced: true

zookeeper
label: ZooKeeper service discovery
template:
    jdbc:hive2://{zk1}:2181,{zk2}:2181,{zk3}:2181/;serviceDiscoveryMode=zooKeeper;zooKeeperNamespace=hiveserver2
advanced: true

ssl
label: HiveServer2 / SSL
template:
    jdbc:hive2://{host}:10000/{database};ssl=true
advanced: true


----------------------------------------------------------------------
DATABRICKS JDBC 3+
----------------------------------------------------------------------

driverId: databricks
recentFamily: databricks
defaultPort: 443

templates:

compute
label: Databricks compute
template:
    jdbc:databricks://{serverHostname}:443;httpPath={httpPath}

schema
label: Databricks compute + schema
template:
    jdbc:databricks://{serverHostname}:443/{schema};httpPath={httpPath}

catalog-schema
label: Databricks compute + catalog + schema
template:
    jdbc:databricks://{serverHostname}:443/{schema};httpPath={httpPath};Catalog={catalog}

These are for the current Databricks JDBC Driver 3+.
Do not copy old Simba-only URL assumptions into the current resource.


----------------------------------------------------------------------
CLICKHOUSE
----------------------------------------------------------------------

driverId: clickhouse
recentFamily: clickhouse

templates:

http-short
label: HTTP / ch prefix
template:
    jdbc:ch://{host}:8123/{database}

http-explicit
label: HTTP / explicit protocol
template:
    jdbc:clickhouse:http://{host}:8123/{database}

https
label: HTTPS
template:
    jdbc:ch:https://{host}:8443/{database}

https-long
label: HTTPS / clickhouse prefix
template:
    jdbc:clickhouse:https://{host}:8443/{database}?ssl=true

Current URL grammar is:

    jdbc:(ch|clickhouse)[:protocol]://endpoint[:port][/database][?parameters]


----------------------------------------------------------------------
DUCKDB
----------------------------------------------------------------------

driverId: duckdb
recentFamily: duckdb

templates:

memory-private
label: Private in-memory database
template:
    jdbc:duckdb:

memory-explicit
label: Private in-memory database / explicit
template:
    jdbc:duckdb:memory:

memory-named
label: Named shared in-memory database
template:
    jdbc:duckdb:memory:{name}

file
label: File database
template:
    jdbc:duckdb:{path}

file-options
label: File + configuration
template:
    jdbc:duckdb:{path};threads={threads};memory_limit={memoryLimit}
advanced: true

ducklake
label: DuckLake
template:
    jdbc:duckdb:ducklake:{metadataPath}
advanced: true


----------------------------------------------------------------------
SINGLESTORE
----------------------------------------------------------------------

driverId: singlestore
recentFamily: singlestore
defaultPort: 3306

templates:

basic
label: SingleStore
template:
    jdbc:singlestore://{host}:3306/{database}

default-port
label: SingleStore / default port
template:
    jdbc:singlestore://{host}/{database}

sequential
label: Sequential failover
template:
    jdbc:singlestore:sequential://{host1}:3306,{host2}:3306/{database}

loadbalance
label: Load balancing
template:
    jdbc:singlestore:loadbalance://{host1}:3306,{host2}:3306/{database}

host-description
label: Host property syntax
template:
    jdbc:singlestore://address=(host={host})(port=3306)/{database}
advanced: true


----------------------------------------------------------------------
GOOGLE CLOUD SPANNER
----------------------------------------------------------------------

driverId: spanner
recentFamily: spanner

templates:

basic
label: Cloud Spanner
template:
    jdbc:cloudspanner:/projects/{project}/instances/{instance}/databases/{database}

emulator
label: Local Spanner emulator / auto configure
template:
    jdbc:cloudspanner:/projects/{project}/instances/{instance}/databases/{database};autoConfigEmulator=true
advanced: true
note:
    autoConfigEmulator targets the local emulator and can create the instance
    and database if necessary.


----------------------------------------------------------------------
APACHE IGNITE 2
----------------------------------------------------------------------

driverId: ignite2
recentFamily: ignite2
defaultPort: 10800

templates:

basic
label: Ignite Thin
template:
    jdbc:ignite:thin://{host}:10800

schema
label: Ignite Thin / schema
template:
    jdbc:ignite:thin://{host}:10800/{schema}

multiple
label: Multiple endpoints
template:
    jdbc:ignite:thin://{host1}:10800,{host2}:10800/{schema}

port-range
label: Endpoint port range
template:
    jdbc:ignite:thin://{host}:10800..10810/{schema}
advanced: true

semicolon-schema
label: Semicolon property syntax
template:
    jdbc:ignite:thin://{host}:10800;schema={schema}
advanced: true


----------------------------------------------------------------------
MONGODB ATLAS SQL JDBC
----------------------------------------------------------------------

driverId: mongodb-atlas
recentFamily: mongodb-atlas

templates:

atlas-sql
label: MongoDB Atlas SQL endpoint
template:
    jdbc:mongodb://{atlasSqlHost}/{database}

atlas-sql-options
label: MongoDB Atlas SQL endpoint + options
template:
    jdbc:mongodb://{atlasSqlHost}/{database}?{option}={value}
advanced: true

IMPORTANT:
This is the MongoDB JDBC/Atlas SQL interface.

Do NOT invent normal MongoDB localhost/replica-set JDBC suggestions merely
because native MongoDB connection strings have that shape.

The JDBC URL should normally use the SQL endpoint supplied by Atlas.

Never place username/password into the standard URL even though MongoDB URI
grammar permits credentials before '@'.


----------------------------------------------------------------------
NEO4J
----------------------------------------------------------------------

driverId: neo4j
recentFamily: neo4j
defaultPort: 7687

templates:

basic
label: Neo4j
template:
    jdbc:neo4j://{host}:7687/{database}

server
label: Neo4j / database omitted
template:
    jdbc:neo4j://{host}:7687

secure
label: Neo4j / TLS + CA validation
template:
    jdbc:neo4j+s://{host}:7687/{database}

secure-self-signed
label: Neo4j / TLS + self-signed certificate
template:
    jdbc:neo4j+ssc://{host}:7687/{database}
advanced: true
note:
    Intended for self-signed certificates; do not reinterpret this as an
    instruction to disable all certificate handling.


----------------------------------------------------------------------
AMAZON ATHENA JDBC 3.x
----------------------------------------------------------------------

driverId: athena
recentFamily: athena

templates:

region
label: Athena / region + workgroup
template:
    jdbc:athena://Region={region};WorkGroup={workgroup};

context
label: Athena / catalog + database + workgroup
template:
    jdbc:athena://Region={region};Catalog={catalog};Database={database};WorkGroup={workgroup};

output
label: Athena / explicit output location
template:
    jdbc:athena://Region={region};Catalog={catalog};Database={database};WorkGroup={workgroup};OutputLocation=s3://{bucket}/{prefix};
note:
    OutputLocation may be omitted when the selected workgroup supplies one.

legacy-v2-prefix
label: Athena v2 compatibility prefix
template:
    jdbc:awsathena://Region={region};Workgroup={workgroup};
legacy: true
note:
    JDBC 3.x supports the v2 prefix for compatibility, but AWS deprecates it.
    Prefer jdbc:athena://.

Never include AWS access key, secret key, session token, or other credentials.


----------------------------------------------------------------------
AMAZON DOCUMENTDB JDBC
----------------------------------------------------------------------

driverId: documentdb
recentFamily: documentdb
defaultPort: 27017

templates:

basic
label: Amazon DocumentDB
template:
    jdbc:documentdb://{clusterEndpoint}:27017/{database}

default-port
label: Amazon DocumentDB / default port
template:
    jdbc:documentdb://{clusterEndpoint}/{database}

scan
label: Amazon DocumentDB / scan options
template:
    jdbc:documentdb://{clusterEndpoint}:27017/{database}?scanMethod=idForward&scanLimit={scanLimit}
advanced: true

Do NOT add tlsAllowInvalidHostnames=true or similar TLS-validation bypasses.


----------------------------------------------------------------------
ELASTICSEARCH SQL JDBC
----------------------------------------------------------------------

driverId: elasticsearch
recentFamily: elasticsearch
defaultPort: 9200

templates:

basic
label: Elasticsearch SQL
template:
    jdbc:es://{host}:9200/

long-prefix
label: Elasticsearch SQL / long prefix
template:
    jdbc:elasticsearch://{host}:9200/

https
label: Elasticsearch SQL / HTTPS
template:
    jdbc:es://https://{host}:9200/

https-options
label: Elasticsearch SQL / HTTPS + timezone
template:
    jdbc:es://https://{host}:9200/?timezone=UTC

Driver grammar supports both `es` and `elasticsearch` prefixes.


----------------------------------------------------------------------
SAP / SYBASE ASE jCONNECT
----------------------------------------------------------------------

driverId: sybase-ase
recentFamily: sybase-ase

templates:

basic
label: SAP ASE / jConnect
template:
    jdbc:sybase:Tds:{host}:{port}

database
label: SAP ASE / database
template:
    jdbc:sybase:Tds:{host}:{port}/{database}

properties
label: SAP ASE / URL properties
template:
    jdbc:sybase:Tds:{host}:{port}/{database}?{property}={value}
advanced: true

sap-prefix
label: SAP ASE / current SAP prefix
template:
    jdbc:sapase:Tds:{host}:{port}/{database}
advanced: true
note:
    Supported by current SAP jConnect. The historical jdbc:sybase:Tds prefix
    remains important for compatibility.

Do not assume a universal ASE listener port.


----------------------------------------------------------------------
DENODO VDP
----------------------------------------------------------------------

driverId: denodo
recentFamily: denodo
defaultPort: 9999

templates:

basic
label: Denodo VDP
template:
    jdbc:denodo://{host}:9999/{database}

default-port
label: Denodo VDP / default port
template:
    jdbc:denodo://{host}/{database}

ssl
label: Denodo VDP / SSL
template:
    jdbc:denodo://{host}:9999/{database}?ssl=true

legacy-vdb
label: Legacy VDB prefix
template:
    jdbc:vdb://{host}:9999/{database}
legacy: true


----------------------------------------------------------------------
MIMER SQL
----------------------------------------------------------------------

driverId: mimer
recentFamily: mimer
defaultPort: 1360

templates:

basic
label: Mimer SQL
template:
    jdbc:mimer://{host}:1360/{database}

default-port
label: Mimer SQL / default port
template:
    jdbc:mimer://{host}/{database}

properties
label: Mimer SQL / property form
template:
    jdbc:mimer:?databaseName={database}&serverName={host}&portNumber=1360
advanced: true

local
label: Mimer SQL / local native connection
template:
    jdbc:mimer:local:///{database}
advanced: true
note:
    Requires the applicable local/native Mimer communication components.


----------------------------------------------------------------------
PROGRESS OPENEDGE
----------------------------------------------------------------------

driverId: openedge
recentFamily: openedge

templates:

basic
label: OpenEdge
template:
    jdbc:datadirect:openedge://{host}:{port};databaseName={database};

service-name
label: OpenEdge / service name
template:
    jdbc:datadirect:openedge://{host}:-1;databaseName={database};servicename={serviceName};
advanced: true

schema
label: OpenEdge / default schema
template:
    jdbc:datadirect:openedge://{host}:{port};databaseName={database};defaultSchema={schema};

No universal OpenEdge SQL listener port should be hard-coded.


----------------------------------------------------------------------
APACHE PHOENIX
----------------------------------------------------------------------

driverId: phoenix
recentFamily: phoenix

templates:

configuration
label: Phoenix / HBase configuration
template:
    jdbc:phoenix
note:
    Uses HBase configuration available to the client.

zookeeper
label: ZooKeeper registry
template:
    jdbc:phoenix:{zk1},{zk2},{zk3}:2181:/hbase

zookeeper-explicit
label: ZooKeeper registry / explicit protocol
template:
    jdbc:phoenix+zk:{zk1},{zk2},{zk3}:2181:/hbase

master-registry
label: HBase master registry
template:
    jdbc:phoenix+master:{master1},{master2}
advanced: true

rpc-registry
label: HBase RPC registry
template:
    jdbc:phoenix+rpc:{server1}\:{port1},{server2}\:{port2}
advanced: true
note:
    The colon between RPC host and port is escaped according to Phoenix URL
    grammar.

query-server-thin
label: Phoenix Query Server / Thin
template:
    jdbc:phoenix:thin:url=http://{host}:8765
advanced: true
note:
    Applies to the Phoenix thin/Avatica Query Server driver rather than the
    normal thick Phoenix JDBC driver.


========================================================================
7. COMPATIBILITY / ALIAS DRIVERS
========================================================================

Do NOT duplicate the following resources.

Use inheritance.

----------------------------------------------------------------------
POSTGRESQL-PROTOCOL FAMILY
----------------------------------------------------------------------

cockroachdb
    inherit: postgresql
    recentFamily: postgresql

greenplum
    inherit: postgresql
    recentFamily: postgresql

yugabytedb
    inherit: postgresql
    recentFamily: postgresql

timescaledb
    inherit: postgresql
    recentFamily: postgresql

citus
    inherit: postgresql
    recentFamily: postgresql

aurora-postgresql
    inherit: postgresql
    recentFamily: postgresql


----------------------------------------------------------------------
MYSQL-PROTOCOL FAMILY
----------------------------------------------------------------------

tidb
    inherit: mysql
    recentFamily: mysql

oceanbase-mysql
    inherit: mysql
    recentFamily: mysql

aurora-mysql
    inherit: mysql
    recentFamily: mysql

Do NOT substitute MariaDB's removed jdbc:mariadb:aurora mode for
aurora-mysql. The normal MySQL protocol is the portable standard here.


----------------------------------------------------------------------
AZURE SQL
----------------------------------------------------------------------

driverId: azure-sql
inherit: sqlserver
recentFamily: sqlserver

PREPEND the following Azure-specific template:

azure
label: Azure SQL Database
template:
    jdbc:sqlserver://{server}.database.windows.net:1433;databaseName={database};encrypt=true;trustServerCertificate=false


----------------------------------------------------------------------
SQL SERVER LOCALDB
----------------------------------------------------------------------

driverId: sqlserver-localdb
recentFamily: sqlserver-localdb

templates: []

note:
    Do not invent a JDBC equivalent of .NET's
    `(localdb)\MSSQLLocalDB` syntax.

    LocalDB connectivity depends on Microsoft SQL Server/LocalDB environment
    details and is not a generally portable JDBC URL morphology.

    The user may enter a URL manually and it can subsequently appear under
    Recents after successful use.


----------------------------------------------------------------------
SPARK THRIFT SERVER
----------------------------------------------------------------------

driverId: spark-thrift
inherit: hive
recentFamily: hive


========================================================================
8. TYPES FOR WHICH THERE IS NO SINGLE BUILT-IN STANDARD JDBC URL
========================================================================

These IDs still need entries in the resource so that coverage is explicit.

Do NOT fabricate URL syntax merely to make the Standard group non-empty.


----------------------------------------------------------------------
TARANTOOL
----------------------------------------------------------------------

driverId: tarantool
recentFamily: tarantool
templates: []

note:
    There is no universal maintained JDBC protocol associated with the built-in
    Tarantool type. Standard templates must come from an explicitly configured
    third-party/custom JDBC driver.


----------------------------------------------------------------------
COUCHBASE
----------------------------------------------------------------------

driverId: couchbase
recentFamily: couchbase
templates: []

note:
    Couchbase has Java/native APIs and third-party/partner JDBC products, but
    there is no single universal Couchbase JDBC URL which can safely be attached
    to an arbitrary JDBC driver.


----------------------------------------------------------------------
CASSANDRA
----------------------------------------------------------------------

driverId: cassandra
recentFamily: cassandra
templates: []

note:
    Cassandra's official Java driver is not JDBC. Only show Standard JDBC
    templates if the user installs/configures a concrete third-party JDBC
    driver that declares them.


----------------------------------------------------------------------
REDIS
----------------------------------------------------------------------

driverId: redis
recentFamily: redis
templates: []

note:
    Redis Java clients such as Jedis are not JDBC drivers. Do not fabricate a
    jdbc:redis URL.


----------------------------------------------------------------------
DYNAMODB
----------------------------------------------------------------------

driverId: dynamodb
recentFamily: dynamodb
templates: []

note:
    There is no universal official general-purpose DynamoDB JDBC protocol.
    Third-party JDBC products must supply their own templates.


----------------------------------------------------------------------
GENERIC / CUSTOM JDBC
----------------------------------------------------------------------

driverId: generic-jdbc
recentFamily: generic-jdbc
templates: []

The custom JDBC driver definition may itself optionally declare:

    jdbcUrlTemplates[]

If it does, merge those into Standard.

Otherwise Generic JDBC displays Recents only.

Never infer a JDBC URL merely from the driver's class name or Maven artifact.


========================================================================
9. RESOURCE VALIDATION
========================================================================

Validate the catalog during tests and preferably fail fast during development.

Tests must assert:

1. Every built-in Database Manager driver/type ID has an explicit URL-template
   catalog entry OR an explicit inheritance entry.

2. No orphan resource entries reference nonexistent driver IDs.

3. Template IDs are unique within the effective inherited driver.

4. Inheritance has no cycles.

5. Every nonempty Standard template begins with `jdbc:`.

6. Every Standard template is nonblank.

7. No Standard template contains literal credentials or sensitive placeholders
   such as:
       {password}
       {token}
       {secret}
       {accessKey}
       {apiKey}

8. No Standard template contains known TLS bypasses such as:
       trustServerCertificate=true
       SSLVerification=NONE
       NOCERTCHECK
       tlsAllowInvalidHostnames=true
       validateservercertificate=0

9. Aliases resolve correctly.

10. Resource parsing must not depend upon map iteration order.


========================================================================
10. RECENTS TESTS
========================================================================

Add tests for:

- empty history;
- MRU insertion;
- most-recent-first ordering;
- exact deduplication;
- moving an existing URL back to position 0;
- maximum 12 entries;
- persistence/reload;
- per-family separation;
- PostgreSQL aliases sharing PostgreSQL history;
- MySQL aliases sharing MySQL history;
- Spark/Hive sharing history;
- sensitive URL suppression;
- ordinary safe query parameters still being remembered;
- URLs containing `password=`, `PWD=`, `token=`, etc. not being persisted;
- URLs containing user:password@host not being persisted.


========================================================================
11. UI TESTS
========================================================================

Add tests, where the existing TUI test infrastructure permits, proving that:

- PostgreSQL shows PostgreSQL Standard suggestions;
- switching to Oracle replaces Standard with Oracle forms;
- switching driver does NOT overwrite manually typed text;
- H2 exposes mem/file/TCP/SSL variants;
- SQLite exposes file/memory/resource but not TCP;
- Oracle exposes service/TNS/SID/descriptor variants;
- MySQL exposes normal/loadbalance/replication/SRV variants;
- MariaDB exposes its current connector modes;
- SQL Server exposes host/instance/TLS/Kerberos variants;
- Athena exposes current jdbc:athena and marks jdbc:awsathena legacy;
- a product whose Standard list is empty still permits manual editing;
- Recents appear above Standard;
- empty groups disappear;
- group headings cannot be selected;
- selecting a template inserts its literal template;
- selecting a recent inserts the complete recent URL;
- closing the popup with Esc leaves the value unchanged.


========================================================================
12. MORPHOLOGICAL TESTS
========================================================================

Add a resource-level morphology test with driver-specific prefix expectations.

At minimum:

postgresql         jdbc:postgresql:
mysql              jdbc:mysql: / jdbc:mysql+srv:
mariadb            jdbc:mariadb:
oracle             jdbc:oracle:
sqlserver          jdbc:sqlserver:
db2                jdbc:db2:
sqlite             jdbc:sqlite:
h2                 jdbc:h2:
hsqldb             jdbc:hsqldb:
derby-*            jdbc:derby:
firebird           jdbc:firebird: / jdbc:firebirdsql:
informix           jdbc:informix-sqli:
iris               jdbc:IRIS:
tibero             jdbc:tibero:
teradata           jdbc:teradata:
vertica            jdbc:vertica:
exasol             jdbc:exa:
sap-hana           jdbc:sap:
snowflake          jdbc:snowflake:
redshift           jdbc:redshift:
bigquery           jdbc:bigquery:
trino              jdbc:trino:
presto             jdbc:presto:
hive               jdbc:hive2:
databricks         jdbc:databricks:
clickhouse         jdbc:ch: / jdbc:clickhouse:
duckdb             jdbc:duckdb:
singlestore        jdbc:singlestore:
spanner            jdbc:cloudspanner:
ignite2            jdbc:ignite:thin:
mongodb-atlas      jdbc:mongodb:
neo4j              jdbc:neo4j:
athena             jdbc:athena: plus legacy jdbc:awsathena:
documentdb         jdbc:documentdb:
elasticsearch      jdbc:es: / jdbc:elasticsearch:
sybase-ase         jdbc:sybase:Tds: / jdbc:sapase:Tds:
denodo             jdbc:denodo: plus legacy jdbc:vdb:
mimer              jdbc:mimer:
openedge           jdbc:datadirect:openedge:
phoenix            jdbc:phoenix


========================================================================
13. IMPLEMENTATION ARCHITECTURE
========================================================================

Keep responsibilities separate.

Suggested logical components:

    JdbcUrlTemplateRepository
        loads immutable Standard templates from resources

    JdbcUrlRecentRepository
        loads/saves local MRU values

    JdbcUrlSuggestionService
        combines:
            Recents
            Standard
        for the selected driver

    JdbcUrlSuggestion
        group
        label
        value
        description
        advanced
        legacy

The dialog must consume JdbcUrlSuggestionService rather than knowing how
resources or history are stored.

Do not put filesystem I/O in the TUI widget.

Do not put driver-specific `when(driverId)` URL strings in the UI.


========================================================================
14. IMPORTANT SEMANTICS
========================================================================

The templates are SUGGESTIONS, not formal URL validators.

That distinction matters because:

- JDBC drivers acquire new URL properties over time;
- custom JDBC drivers exist;
- users can have vendor-specific extensions;
- JDBC URL properties can be supplied separately through Properties;
- aliases can use the same underlying driver;
- some drivers have version-specific URL features.

Do NOT reject a user's URL merely because it does not match one of these
templates.

If a loaded java.sql.Driver is available, Driver.acceptsURL(url) may be used
as a useful diagnostic, but Test Connection remains the meaningful final check.


========================================================================
15. ADVANCED / LEGACY DISPLAY
========================================================================

Do not hide `advanced` or `legacy` entries by default unless the existing UI
already has a suitable mechanism.

They should remain selectable.

If space permits, render metadata such as:

    SID                                  legacy
    Full DESCRIPTION                    advanced
    ZooKeeper service discovery         advanced

but do not clutter the actual JDBC URL value with those labels.


========================================================================
16. ACCEPTANCE CRITERIA
========================================================================

The implementation is complete when:

- JDBC URL is still freely editable;
- selecting a driver updates its Standard suggestions;
- every built-in JDBC type is covered by the catalog;
- JDBC aliases inherit correct templates;
- no URL template is hard-coded into dialog code;
- Recents and Standard are visibly separated;
- Recents are MRU and persisted locally;
- sensitive URLs are never persisted as recents;
- templates contain no credentials;
- the full feature is keyboard-operable in the TUI;
- selecting a suggestion copies it into the JDBC URL field for further editing;
- custom JDBC drivers remain possible;
- unsupported/non-universal JDBC types do not receive invented syntax;
- existing Database Manager functionality and connection persistence continue
  to work;
- tests cover resource integrity, inheritance, recents, security, and the
  principal JDBC morphology variants.

Before changing code, inspect the existing Database Manager driver catalog,
resource-loading infrastructure, configuration-directory abstraction,
dialog/form controls, combo/popup widgets, and tests. Reuse those mechanisms
instead of introducing parallel infrastructure.
