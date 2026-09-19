Implement the following built-in JDBC driver catalog.

RULES
- Prefer Maven Central.
- Do NOT hard-code a driver version permanently.
- Catalog entries contain Maven group:artifact plus optional classifier.
- Resolve the latest stable/non-SNAPSHOT version compatible with the running JVM.
- Once downloaded, persist the resolved version; NEVER silently upgrade it.
- Allow the user to select another version.
- Store downloaded driver + runtime dependencies under Kode's JDBC plugin directory.
- Load each driver with an isolated classloader.
- Prefer JDBC Service Provider discovery (META-INF/services/java.sql.Driver).
- driverClass below is a fallback/override, not the primary discovery mechanism.
- Always support CUSTOM_JDBC:
    * arbitrary Maven coordinates
    * arbitrary Maven repository
    * local JAR/JARs
    * optional explicit driver class
- Some DBMSes deliberately reuse another vendor's protocol/driver; do NOT download a duplicate driver for aliases.

FORMAT:
id | displayName | Maven/source | driverClass | urlPrefix

CORE RELATIONAL

postgresql | PostgreSQL | org.postgresql:postgresql | org.postgresql.Driver | jdbc:postgresql:
mysql | MySQL | com.mysql:mysql-connector-j | com.mysql.cj.jdbc.Driver | jdbc:mysql:
mariadb | MariaDB | org.mariadb.jdbc:mariadb-java-client | org.mariadb.jdbc.Driver | jdbc:mariadb:
oracle | Oracle | com.oracle.database.jdbc:ojdbc11 | oracle.jdbc.OracleDriver | jdbc:oracle:
sqlserver | Microsoft SQL Server | com.microsoft.sqlserver:mssql-jdbc | com.microsoft.sqlserver.jdbc.SQLServerDriver | jdbc:sqlserver:
db2 | IBM Db2 LUW | com.ibm.db2:jcc | com.ibm.db2.jcc.DB2Driver | jdbc:db2:
sqlite | SQLite | org.xerial:sqlite-jdbc | org.sqlite.JDBC | jdbc:sqlite:
h2 | H2 | com.h2database:h2 | org.h2.Driver | jdbc:h2:
hsqldb | HSQLDB / HyperSQL | org.hsqldb:hsqldb | org.hsqldb.jdbc.JDBCDriver | jdbc:hsqldb:
derby-embedded | Apache Derby Embedded | org.apache.derby:derby | org.apache.derby.jdbc.EmbeddedDriver | jdbc:derby:
derby-client | Apache Derby Network | org.apache.derby:derbyclient | org.apache.derby.jdbc.ClientDriver | jdbc:derby:
firebird | Firebird | org.firebirdsql.jdbc:jaybird | org.firebirdsql.jdbc.FBDriver | jdbc:firebirdsql:
informix | IBM Informix | com.ibm.informix:jdbc | com.informix.jdbc.IfxDriver | jdbc:informix-sqli:
iris | InterSystems IRIS | com.intersystems:intersystems-jdbc | com.intersystems.jdbc.IRISDriver | jdbc:IRIS:
tibero | TmaxTibero | com.tmaxtibero:tbjdbc8 | com.tmax.tibero.jdbc.TbDriver | jdbc:tibero:thin:
teradata | Teradata Vantage | com.teradata.jdbc:terajdbc | com.teradata.jdbc.TeraDriver | jdbc:teradata:
vertica | Vertica | com.vertica.jdbc:vertica-jdbc | com.vertica.jdbc.Driver | jdbc:vertica:
exasol | Exasol | com.exasol:exasol-jdbc | com.exasol.jdbc.EXADriver | jdbc:exa:
sap-hana | SAP HANA | com.sap.cloud.db.jdbc:ngdbc | com.sap.db.jdbc.Driver | jdbc:sap:

ANALYTICS / WAREHOUSE / DISTRIBUTED SQL

snowflake | Snowflake | net.snowflake:snowflake-jdbc | net.snowflake.client.jdbc.SnowflakeDriver | jdbc:snowflake:
redshift | Amazon Redshift | com.amazon.redshift:redshift-jdbc42 | com.amazon.redshift.jdbc.Driver | jdbc:redshift:
bigquery | Google BigQuery | com.google.cloud:google-cloud-bigquery-jdbc classifier=all | discover | jdbc:bigquery:
trino | Trino | io.trino:trino-jdbc | io.trino.jdbc.TrinoDriver | jdbc:trino:
presto | PrestoDB | com.facebook.presto:presto-jdbc | com.facebook.presto.jdbc.PrestoDriver | jdbc:presto:
hive | Apache Hive | org.apache.hive:hive-jdbc | org.apache.hive.jdbc.HiveDriver | jdbc:hive2:
databricks | Databricks | com.databricks:databricks-jdbc | discover | jdbc:databricks:
clickhouse | ClickHouse | com.clickhouse:clickhouse-jdbc-all | com.clickhouse.jdbc.ClickHouseDriver | jdbc:clickhouse:
duckdb | DuckDB | org.duckdb:duckdb_jdbc | org.duckdb.DuckDBDriver | jdbc:duckdb:
singlestore | SingleStore | com.singlestore:singlestore-jdbc-client | discover | jdbc:singlestore:
spanner | Google Cloud Spanner | com.google.cloud:google-cloud-spanner-jdbc | com.google.cloud.spanner.jdbc.JdbcDriver | jdbc:cloudspanner:
ignite2 | Apache Ignite 2 | org.apache.ignite:ignite-core | org.apache.ignite.IgniteJdbcThinDriver | jdbc:ignite:thin:
mongodb-atlas | MongoDB Atlas SQL | org.mongodb:mongodb-jdbc | discover | jdbc:mongodb:
neo4j | Neo4j | org.neo4j:neo4j-jdbc | discover | jdbc:neo4j:

CLOUD / SPECIAL

athena | Amazon Athena | VENDOR: AWS Athena JDBC 3.x official uber-JAR | com.amazon.athena.jdbc.AthenaDriver | jdbc:athena:
documentdb | Amazon DocumentDB SQL/JDBC | VENDOR: AWS DocumentDB official JDBC driver | discover | jdbc:documentdb:
elasticsearch | Elasticsearch SQL JDBC | ELASTIC_MAVEN: org.elasticsearch.plugin:x-pack-sql-jdbc | org.elasticsearch.xpack.sql.jdbc.EsDriver | jdbc:es:

COMPATIBILITY ALIASES -- REUSE DRIVER, NO EXTRA DOWNLOAD

cockroachdb | CockroachDB | ALIAS postgresql | org.postgresql.Driver | jdbc:postgresql:
greenplum | Greenplum | ALIAS postgresql | org.postgresql.Driver | jdbc:postgresql:
yugabytedb | YugabyteDB YSQL | ALIAS postgresql | org.postgresql.Driver | jdbc:postgresql:
timescaledb | TimescaleDB | ALIAS postgresql | org.postgresql.Driver | jdbc:postgresql:
citus | Citus | ALIAS postgresql | org.postgresql.Driver | jdbc:postgresql:
aurora-postgresql | Amazon Aurora PostgreSQL | ALIAS postgresql | org.postgresql.Driver | jdbc:postgresql:

tidb | TiDB | ALIAS mysql | com.mysql.cj.jdbc.Driver | jdbc:mysql:
oceanbase-mysql | OceanBase MySQL mode | ALIAS mysql | com.mysql.cj.jdbc.Driver | jdbc:mysql:
aurora-mysql | Amazon Aurora MySQL | ALIAS mysql | com.mysql.cj.jdbc.Driver | jdbc:mysql:

azure-sql | Azure SQL Database | ALIAS sqlserver | com.microsoft.sqlserver.jdbc.SQLServerDriver | jdbc:sqlserver:
sqlserver-localdb | Microsoft SQL Server LocalDB | ALIAS sqlserver | com.microsoft.sqlserver.jdbc.SQLServerDriver | jdbc:sqlserver:

spark-thrift | Apache Spark SQL / Thrift Server | ALIAS hive | org.apache.hive.jdbc.HiveDriver | jdbc:hive2:

NO RELIABLE PUBLIC MAVEN-CENTRAL JDBC ARTIFACT -- SUPPORT THROUGH CUSTOM/VENDOR JAR

sybase-ase | SAP/Sybase ASE | VENDOR jConnect jconn4.jar | com.sybase.jdbc4.jdbc.SybDriver | jdbc:sybase:Tds:
denodo | Denodo VDP | VENDOR Denodo JDBC driver | discover | jdbc:vdb:
mimer | Mimer SQL | VENDOR Mimer JDBC | discover | jdbc:mimer:
openedge | Progress OpenEdge | VENDOR OpenEdge JDBC | com.ddtek.jdbc.openedge.OpenEdgeDriver | jdbc:datadirect:openedge:
phoenix | Apache Phoenix | Apache Phoenix distribution / version-specific client artifact | org.apache.phoenix.jdbc.PhoenixDriver | jdbc:phoenix:
tarantool | Tarantool | CUSTOM_JDBC only unless a maintained JDBC driver is explicitly configured | discover | -
couchbase | Couchbase | CUSTOM/VENDOR JDBC only | discover | -
cassandra | Apache Cassandra | NO official JDBC API; custom JDBC adapter only | discover | -
redis | Redis | NO official JDBC API; do not pretend the normal Redis Java client is JDBC | - | -
dynamodb | Amazon DynamoDB | NO official general-purpose JDBC driver; custom/vendor adapter only | - | -

IMPORTANT IMPLEMENTATION DETAIL

Represent driver downloads as:

JdbcDriverArtifact {
    repositoryType: MAVEN_CENTRAL | MAVEN_CUSTOM | VENDOR_URL | LOCAL_FILE
    groupId?
    artifactId?
    version?
    classifier?
    repository?
    vendorDownloadPage?
}

A Maven artifact may have runtime dependencies. Download the complete runtime dependency graph, not merely the top-level JAR, unless an official shaded/uber artifact is selected.

For BigQuery prefer:
    com.google.cloud:google-cloud-bigquery-jdbc:<version>:all
because it is suitable as a self-contained driver installation.

For ClickHouse prefer:
    com.clickhouse:clickhouse-jdbc-all
for plugin-style isolated deployment.

For Athena use the official AWS JDBC 3.x uber-JAR rather than confusing it with the unrelated AWS Athena Query Federation Maven artifact.

Driver installation logic must therefore support BOTH Maven artifacts and vendor/direct JAR downloads.

Finally, the database type list and the driver list must be independent:
one JDBC driver may serve several database products (PostgreSQL family, MySQL family, SQL Server/Azure, Hive/Spark).
