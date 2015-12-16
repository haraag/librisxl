package whelk.importer

import groovy.util.logging.Slf4j as Log

import whelk.converter.FormatConverter
import whelk.converter.JsonLDLinkCompleterFilter
import whelk.converter.marc.MarcFrameConverter
import whelk.converter.MarcJSONConverter
import whelk.util.Tools
import whelk.util.LegacyIntegrationTools

import java.sql.*
import java.util.concurrent.*
import java.text.*

import whelk.*
import se.kb.libris.util.marc.*
import se.kb.libris.util.marc.io.*

@Log
class MySQLImporter {

    Whelk whelk
    MarcFrameConverter marcFrameConverter
    JsonLDLinkCompleterFilter enhancer

    static final String JDBC_DRIVER = "com.mysql.jdbc.Driver"

    boolean cancelled = false

    ExecutorService queue
    Semaphore tickets

    int startAt = 0

    int addBatchSize = 1000
    int poolSize = 10

    int recordCount
    long startTime

    List<String> eligibleDatasets = null

    List<Document> documentList = []
    ConcurrentHashMap buildingMetaRecord = new ConcurrentHashMap()
    String lastIdentifier = null

    String connectionUrl

    MySQLImporter(Whelk w, String mysqlConnectionUrl) {
        whelk = w
        marcFrameConverter = null
        connectionUrl = mysqlConnectionUrl
    }


    MySQLImporter(Whelk w, MarcFrameConverter mfc, String mysqlConnectionUrl) {
        whelk = w
        marcFrameConverter = mfc
        connectionUrl = mysqlConnectionUrl
    }

    void doImport(String collection, int nrOfDocs = -1, boolean silent = false, boolean picky = true) {
        recordCount = 0
        startTime = System.currentTimeMillis()
        cancelled = false
        Connection conn = null
        PreparedStatement statement = null
        ResultSet resultSet = null


        tickets = new Semaphore(poolSize)
        //queue = Executors.newSingleThreadExecutor()
        //queue = Executors.newWorkStealingPool()
        queue = Executors.newFixedThreadPool(poolSize)

        log.debug("Turning off versioning in storage")
        this.whelk.storage.versioning = false

        eligibleDatasets = [collection]

        try {

            Class.forName(JDBC_DRIVER)

            log.debug("Connecting to database...")
            conn = connectToUri(new URI(connectionUrl))
            conn.setAutoCommit(false)

            if (collection == "auth") {
                log.info("Creating auth load statement.")
                statement = conn.prepareStatement("SELECT auth_id, data FROM auth_record WHERE auth_id > ? AND deleted = 0 ORDER BY auth_id", java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)
                //statement = conn.prepareStatement("SELECT auth_id, data FROM auth_record WHERE auth_id = ? AND deleted = 0 ORDER BY auth_id", java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)
            }
            if (collection == "bib") {
                log.info("Creating bib load statement.")
                statement = conn.prepareStatement("SELECT bib.bib_id, bib.data, auth.auth_id FROM bib_record bib LEFT JOIN auth_bib auth ON bib.bib_id = auth.bib_id WHERE bib.bib_id > ? AND bib.deleted = 0 ORDER BY bib.bib_id", java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)
            }
            if (collection == "hold") {
                log.info("Creating hold load statement.")
                statement = conn.prepareStatement("SELECT mfhd_id, data, bib_id, shortname FROM mfhd_record WHERE mfhd_id > ? AND deleted = 0 ORDER BY mfhd_id", java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)
            }

            if (!statement) {
                throw new Exception("No valid collection selected")
            }
            statement.setFetchSize(Integer.MIN_VALUE)

            int recordId = startAt
            log.info("Starting loading at ID $recordId")

            MarcRecord record = null

            statement.setInt(1, recordId)
            //statement.setInt(1, 146775)
            resultSet = statement.executeQuery()

            log.debug("Query executed. Starting processing ...")
            while(resultSet.next()) {
                recordId = resultSet.getInt(1)
                record = Iso2709Deserializer.deserialize(normalizeString(new String(resultSet.getBytes("data"), "UTF-8")).getBytes("UTF-8"))

                //buildDocument(recordId, record, collection, null)

                if (collection == "auth") {
                    int auth_id = resultSet.getInt("auth_id")
                    if (auth_id > 0) {
                        buildDocument(recordId, record, collection, null)
                    }
                } else if (collection == "bib") {
                    int auth_id = resultSet.getInt("auth_id")
                    if (auth_id > 0) {
                        log.trace("Found auth_id $auth_id for $recordId Adding to oaipmhSetSpecs")
                        buildDocument(recordId, record, collection, "authority:"+auth_id)
                    }
                } else if (collection == "hold") {
                    int bib_id = resultSet.getInt("bib_id")
                    String sigel = resultSet.getString("shortname")
                    if (bib_id > 0) {
                        log.trace("Found bib_id $bib_id for $recordId Adding to oaipmhSetSpecs")
                        buildDocument(recordId, record, collection, "bibid:" + bib_id)
                    }
                    if (sigel) {
                        log.trace("Found sigel $sigel for $recordId Adding to oaipmhSetSpecs")
                        buildDocument(recordId, record, collection, "location:" + sigel)
                    }
                }
                if (nrOfDocs > 0 && recordCount > nrOfDocs) {
                    log.info("Max docs reached. Breaking.")
                    break
                }
                if (cancelled) {
                    break
                }
            }
            log.debug("Clearing out remaining docs ...")
            buildDocument(null, null, collection, null)
            buildDocument(null, null, collection, null)

            queue.execute({
                log.debug("Resetting versioning setting for storages")
                this.whelk.storage.versioning = true
            } as Runnable)

            queue.shutdown()
            queue.awaitTermination(7, TimeUnit.DAYS)

        } catch(SQLException se) {
            log.error("SQL Exception", se)
        } catch(Exception e) {
            log.error("Exception", e)
        } finally {
            log.info("Record count: ${recordCount}. Elapsed time: " + (System.currentTimeMillis() - startTime) + " milliseconds for sql results.")
            close(conn, statement, resultSet)
        }


        log.info("Import has completed in " + (System.currentTimeMillis() - startTime) + " milliseconds.")
        //return new ImportResult(numberOfDocuments: recordCount, lastRecordDatestamp: null) // TODO: Add correct last document datestamp
    }

    void buildDocument(Integer recordId, MarcRecord record, String type, String oaipmhSetSpecValue) {
        String identifier = null
        String collection = type
        if (documentList.size() >= addBatchSize || record == null) {
            if (tickets.availablePermits() < 1) {
                if (!log.isDebugEnabled()) {
                    Tools.printSpinner("Holding for available queues at $recordCount documents ", recordCount)
                }
                log.debug("At $recordCount documents loaded: Queues are full at the moment. Waiting for some to finish.")
            }
            tickets.acquire()
            log.debug("Doclist has reached batch size. Sending it to bulkAdd (open the trapdoor)")

            //def casr = new ConvertAndStoreRunner(whelk, marcFrameConverter, enhancer, documentList, tickets)
            //casr.run()
            queue.execute(new ConvertAndStoreRunner(whelk, marcFrameConverter, documentList, tickets))
            /*
            log.debug("     Current poolsize: ${queue.poolSize}")
            log.debug("------------------------------")
            log.debug("queuedSubmissionCount: ${queue.queuedSubmissionCount}")
            log.debug("      queuedTaskCount: ${queue.queuedTaskCount}")
            log.debug("   runningThreadCount: ${queue.runningThreadCount}")
            log.debug("    activeThreadCount: ${queue.activeThreadCount}")
            log.debug("------------------------------")
            */
            //log.debug("       completed jobs: ${tickets.availablePermits()}")
            log.debug("Documents stored. Continuing to load rows")
            this.documentList = []
        }
        if (record) {
            def aList = record.getDatafields("599").collect { it.getSubfields("a").data }.flatten()
            if ("SUPPRESSRECORD" in aList) {
                log.debug("Record ${identifier} is suppressed.")
                return
            }
            log.trace("building document $identifier")
            try {
                String oldStyleIdentifier = "/"+type+"/"+record.getControlfields("001").get(0).getData()
                identifier = LegacyIntegrationTools.generateId(oldStyleIdentifier)
                buildingMetaRecord.get(identifier, [:]).put("record", record)
                buildingMetaRecord.get(identifier).put("manifest", [(Document.ID_KEY):identifier,(Document.COLLECTION_KEY):collection, (Document.ALTERNATE_ID_KEY): [oldStyleIdentifier]])
            } catch (Exception e) {
                log.error("Problem getting field 001 from marc record $recordId. Skipping document.", e)
            }
        }
        try {
            if (oaipmhSetSpecValue) {
                buildingMetaRecord.get(identifier, [:]).get("manifest", [:]).get("extraData", [:]).get("oaipmhSetSpecs", []).add(oaipmhSetSpecValue)
            }
            if (lastIdentifier && lastIdentifier != identifier) {
                log.trace("New document received. Adding last ($lastIdentifier}) to the doclist")
                recordCount++
                documentList << buildingMetaRecord.remove(lastIdentifier)
                if (!log.isDebugEnabled()) {
                    Tools.printSpinner("Working. Currently $recordCount documents imported", recordCount)
                }
            }
            lastIdentifier = identifier
        } catch (Exception e) {
            log.error("Problem with marc record ${recordId}. Skipping ...", e)
        }
    }

    class ConvertAndStoreRunner implements Runnable {

        private Whelk whelk
        private MarcFrameConverter converter

        private List recordList

        private Semaphore tickets

        ConvertAndStoreRunner(Whelk w, FormatConverter c, final List recList, Semaphore t) {
            this.whelk = w
            this.converter = c

            this.recordList = recList
            this.tickets = t
        }

        @Override
        void run() {
            try {
                def convertedDocs = [:]
                recordList.each {
                    if (!convertedDocs.containsKey(it.manifest.collection)) { // Create new list
                        convertedDocs.put(it.manifest.collection, [])
                    }
                    it.manifest['contentType'] = "application/x-marc-json"
                    Document doc = new Document(MarcJSONConverter.toJSONMap(it.record), it.manifest)
                    if (converter) {
                        doc = converter.convert(doc)
                    }
                    convertedDocs[(it.manifest.collection)] << doc
                }
                convertedDocs.each { ds, docList ->
                    if (isEligible(ds)) {
                        this.whelk.bulkStore(docList)
                    }
                }
            } finally {
                tickets.release()
            }
        }
    }

    private boolean isEligible(String ds) {
        return (eligibleDatasets == null) || ds in eligibleDatasets
    }


    Connection connectToUri(URI uri) {
        log.info("connect uri: $uri")
        DriverManager.getConnection(uri.toString())
    }

    private void close(Connection conn, PreparedStatement statement, ResultSet resultSet) {
        log.info("Closing down mysql connections.")
        try {
            statement.cancel()
            if (resultSet != null) {
                resultSet.close()
            }
        } catch (SQLException e) {
            log.warn("Exceptions on close. These are safe to ignore.", e)
        } finally {
            try {
                statement.close()
                conn.close()
            } catch (SQLException e) {
                log.warn("Exceptions on close. These are safe to ignore.", e)
            } finally {
                resultSet = null
                statement = null
                conn = null
            }
        }
    }

    String normalizeString(String inString) {
        if (!Normalizer.isNormalized(inString, Normalizer.Form.NFC)) {
            log.trace("Normalizing ...")
            return Normalizer.normalize(inString, Normalizer.Form.NFC)
        }
        return inString
    }
}
