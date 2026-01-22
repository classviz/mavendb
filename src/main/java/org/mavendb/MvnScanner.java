package org.mavendb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Persistence;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.ResourceHandler;
import org.apache.maven.index.reader.WritableResourceHandler;
import org.apache.maven.index.reader.resource.PathWritableResourceHandler;
import org.apache.maven.index.reader.resource.UriResourceHandler;
import org.bson.Document;
import org.mavendb.Main.DatabaseType;

/**
 * Scan all artifacts in maven repository.
 *
 * @see <a href="https://github.com/apache/maven-indexer/blob/master/indexer-reader/src/test/java/org/apache/maven/index/reader/IndexReaderTest.java">IndexReaderTest</a>
 */
@Singleton
@Named
public class MvnScanner {

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(MvnScanner.class.getName());
    private static final String ENTITY_MANAGER_FACTORY = "PUMvn";

    private final URI indexFolder;
    private final DatabaseType dbType;

    /* ------- MySQL ------- */

    private static final int BATCH_SIZE_MYSQL = 1000000;

    /**
     * Database store factory.
     */
    private EntityManagerFactory emf;

    /**
     * Objects to be saved to DB.
     */
    private List<MvnRecord> dbList = new ArrayList<>();

    /* ------- MongoDB ------- */


    public MvnScanner(URI indexFolder, DatabaseType dbType) {
        this.indexFolder = indexFolder;
        this.dbType = dbType;
    }

    public void perform(Properties config) throws IOException {

        if (this.dbType == DatabaseType.MYSQL) {
            this.emf = Persistence.createEntityManagerFactory(ENTITY_MANAGER_FACTORY, config);
            this.stepExecuteSQLScript(Main.getDirectoryFileName(Main.DIR_DB, Main.DB_CREATE_SQL));
        } else if (this.dbType == DatabaseType.MONGODB) {
            MongoClient client = MongoClients.create(config.getProperty("com.mongodb.client.uri"));

        }

        long start = System.currentTimeMillis();
        this.stepScan();
        LOG.log(Level.INFO, "Scan execution time={0}", System.currentTimeMillis() - start);

        // Refresh Data
        this.stepExecuteSQLScript(Main.getDirectoryFileName(Main.DIR_DB, Main.DB_DATA_REFRESH_SQL));
    }

    /**
     * Execute an SQL script.
     *
     * @see <a href="https://wiki.eclipse.org/EclipseLink/Examples/JPA/EMAPI#Getting_a_JDBC_Connection_from_an_EntityManager">Getting a JDBC Connection from an EntityManager</a>
     */
    private void stepExecuteSQLScript(String script) throws IOException{
        try (EntityManager em = emf.createEntityManager(); Reader r = new FileReader(script, StandardCharsets.UTF_8)) {
            em.getTransaction().begin();

            LOG.log(Level.INFO, "SQL {0} execution started", script);
            long start = System.currentTimeMillis();
            ScriptRunner sr = new ScriptRunner(em.unwrap(Connection.class));
            sr.runScript(r);
            LOG.log(Level.INFO, "SQL {0} execution finished, execution time {1} ms", new Object[]{script, System.currentTimeMillis() - start});

            em.getTransaction().commit();
        }
    }


    /**
     * Scan maven index files.
     *
     * @throws IOException Exception
     */
    @SuppressWarnings("java:S3776") // Cognitive Complexity of methods should not be too high
    private void stepScan() throws IOException {
        Path tempDir = Files.createTempDirectory("mvn-index");

        try (
            ResourceHandler remote = new UriResourceHandler(this.indexFolder);
            WritableResourceHandler local = new PathWritableResourceHandler(tempDir);
            IndexReader indexReader = new IndexReader(local, remote)
        ) {
            LOG.log(Level.INFO,"indexRepoId=" + indexReader.getIndexId());
            LOG.log(Level.INFO,"indexLastPublished=" + indexReader.getPublishedTimestamp());
            LOG.log(Level.INFO,"isIncremental=" + indexReader.isIncremental());
            LOG.log(Level.INFO,"indexRequiredChunkNames=" + indexReader.getChunkNames());

            for (ChunkReader chunkReader : indexReader) {

                LOG.log(Level.INFO,"  chunkName=" + chunkReader.getName());
                LOG.log(Level.INFO,"  chunkVersion=" + chunkReader.getVersion());
                LOG.log(Level.INFO,"  chunkPublished=" + chunkReader.getTimestamp());

                // List one by one all recordsin the chunk
                final RecordExpander recordExpander = new RecordExpander();
                long i = 0;
                for (Map<String, String> rec : chunkReader) {
                    i++;
                    final org.apache.maven.index.reader.Record record = recordExpander.apply(rec);
                    JsonObject jsonObject = new JsonObject();
                    record.getExpanded().forEach((k, v) -> {
                        if (k.getProto().equals(String.class)) {
                            jsonObject.addProperty(k.getName(), record.getString(k));
                        } else if (k.getProto().equals(String[].class)) {
                            JsonArray stringArray = new JsonArray();
                            for (String s : record.getStringArray(k)) {
                                stringArray.add(s);
                            }
                            jsonObject.add(k.getName(), stringArray);
                        } else if (k.getProto().equals(Long.class)) {
                            jsonObject.addProperty(k.getName(), record.getLong(k));
                        } else if (k.getProto().equals(Boolean.class)) {
                            jsonObject.addProperty(k.getName(), record.getBoolean(k));
                        } else {
                            LOG.log(Level.WARNING,"Unrecognized key type: " + k + "=" + v + ", name=" + k.getName() + ", type=" + v.getClass().getSimpleName());
                        }
                    });

                    String versioString = record.getString(org.apache.maven.index.reader.Record.VERSION);
                    if (StringUtils.isBlank(versioString)) {
                        LOG.log(Level.WARNING, "Record without version found, skipping: {0}", record);
                        continue;
                    }
                    VersionAnalyser analizedVersion = new VersionAnalyser(versioString);


                    this.add(jsonObject, analizedVersion, i);
                    this.store(false, i);
                }
                this.store(true, i);
            }
        }
    }

    private void add(JsonObject jobj, VersionAnalyser analizedVersion, long i) {
        try (EntityManager em = emf.createEntityManager()) {
            // Prepare the Entity Object
            MvnRecord dbAi = new MvnRecord(i);
            dbAi.setMajorVersion(analizedVersion.getMajorVersion());
            dbAi.setVersionSeq(BigInteger.valueOf(analizedVersion.getVersionSeq()));
            dbAi.setJson(jobj.toString());

            // Add to DB To be saved List
            this.dbList.add(dbAi);

            em.clear();
        }
    }

    /**
     * Store to database.
     *
     * @param force Flag to force save or not
     */
    private void store(final boolean force, final long counter) throws IOException {
        // Nothing to be saved
        if (this.dbList.isEmpty()) {
            return;
        }

        // Save BATCH_SIZE_MYSQL records as a group,
        // Or when force save, save it no matter of the size
        if (this.dbList.size() >= BATCH_SIZE_MYSQL || force) {
            try (EntityManager em = this.emf.createEntityManager()) {
                LocalDateTime begin = LocalDateTime.now();
                em.setFlushMode(FlushModeType.COMMIT);
                em.getTransaction().begin();
                this.dbList.forEach(em::persist);
                em.getTransaction().commit();
                em.clear();

                Duration duration = Duration.between(begin, LocalDateTime.now());
                LOG.log(Level.INFO, "persist finished for records counter={0} in seconds={1}", new Object[]{counter, duration.toSeconds()});
            }

            // Clear the Cached Object
            this.dbList.clear();
            this.dbList = new ArrayList<>(); // Add the code to avoid - java.lang.OutOfMemoryError: GC overhead limit exceeded
        }
    }

    /**
     * Version string analysis result.
     */
    private static final class VersionAnalyser {

        private static final long MAJOR_VERSION_MAX = 922;
        private static final long MAJOR_VERSION_MAX_YEAR = 9223372036L;
        private static final int RADIX_DECIMAL = 10;
        /**
         * Length of a year string. Example: in <code>2000.01.01</code>, so
         * year's length is 4.
         */
        private static final int YEAR_LENGTH = 10;

        private static final String DOT = ".";
        private static final String DOTS = "..";

        /**
         * The major version of the artifact. We don't expect the major version
         * is too big, usually it is 1, 2, 3, etc.
         */
        private final int majorVersionResult;
        private final long versionSeqResult;

        /**
         * Get maven version sequence. The value is generated by the following
         * logic:
         *
         * <pre>
         *   9,22 3,372,036,854,775,807
         *  |- --|- ---|--- ---|--- ---|
         *   Maj  Min  Incre   4th
         *
         * The left 4 digits are Major Version;
         * then 3 digits are Minor Version;
         * then 6 digits are Incremental Version;
         * then the last 4 digits are the fourth Version.
         * </pre>
         *
         * Well in case we suspect the version is a date format, it will be:
         *
         * <pre>
         *   9,223,372,036,854,775,807
         *   - --- --- ---|--- ---|---|
         *            Year   Month Date
         * Example version text
         * - com.hack23.cia           | citizen-intelligence-agency | 2016.12.13
         * - org.everit.osgi.dev.dist | eosgi-dist-felix_5.2.0      | v201604220928
         * - berkano                  | berkano-sample              | 20050805.042414
         * </pre>
         *
         * @param version Version string
         */
        @SuppressWarnings({"checkstyle:MagicNumber", "java:S3776"}) // java:S3776 - Cognitive Complexity of methods should not be too high
        private VersionAnalyser(final String version) {
            if (version == null) {
                this.majorVersionResult = 0;
                this.versionSeqResult = 0;
                return;
            }

            String majorVersionStr = "";
            long majorVersion = 0;
            long minorVersion = 0;
            long increVersion = 0;
            long four4Version = 0;

            String versionTemp = version.replaceAll("[^\\d.]", DOT);
            if (versionTemp.contains(DOT)) {
                while (versionTemp.contains(DOTS)) {
                    versionTemp = versionTemp.replace(DOTS, DOT);
                }
                StringTokenizer tok = new StringTokenizer(versionTemp, DOT);

                if (tok.hasMoreTokens()) {
                    majorVersionStr = tok.nextToken();
                    majorVersion = NumberUtils.toLong(majorVersionStr);
                    majorVersion = (majorVersion > VersionAnalyser.MAJOR_VERSION_MAX) ? VersionAnalyser.MAJOR_VERSION_MAX : majorVersion;
                }
                if (tok.hasMoreTokens()) {
                    minorVersion = NumberUtils.toLong(tok.nextToken());
                }
                if (tok.hasMoreTokens()) {
                    increVersion = NumberUtils.toLong(tok.nextToken());
                }
                if (tok.hasMoreTokens()) {
                    four4Version = NumberUtils.toLong(tok.nextToken());
                }
            } else {
                four4Version = NumberUtils.toLong(versionTemp);
            }

            long seq;
            if (majorVersion == VersionAnalyser.MAJOR_VERSION_MAX) {
                // We suspect the version string is usually a year.month.date
                // So we set major version as the year
                String upTo4char = majorVersionStr.substring(0, Math.min(majorVersionStr.length(), YEAR_LENGTH));
                majorVersion = NumberUtils.toLong(upTo4char);
                seq = shrinkLong(NumberUtils.toLong(majorVersionStr),
                        MAJOR_VERSION_MAX_YEAR) * 1000000000L + shrinkLong(minorVersion, 999999) * 1000L + shrinkLong(increVersion, 999);
            } else {
                // All other cases
                seq = majorVersion * 10000000000000000L
                        + shrinkLong(minorVersion, 9999) * 1000000000000L
                        + shrinkLong(increVersion, 999999) * 1000000L
                        + shrinkLong(four4Version, 999999);
            }

            // Set results
            this.majorVersionResult = (majorVersion >= Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) majorVersion;
            this.versionSeqResult = seq;
        }

        /**
         * Return {@link #majorVersionResult} value.
         *
         * @return Value of {@link #majorVersionResult}
         */
        int getMajorVersion() {
            return this.majorVersionResult;
        }

        /**
         * Return {@link #versionSeqResult} value.
         *
         * @return Value of {@link #versionSeqResult}
         */
        long getVersionSeq() {
            return this.versionSeqResult;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return this.majorVersionResult + ", " + this.versionSeqResult;
        }

        /**
         * Shrink a long value to avoid it exceed the limit.
         *
         * @param value Value to shrink
         * @param limit Max value allowed
         * @return Value no more than the limit
         */
        private long shrinkLong(final long value, final long limit) {
            long temp = value;
            while (temp > limit) {
                temp = temp / RADIX_DECIMAL;
            }

            return temp;
        }
    }

}
