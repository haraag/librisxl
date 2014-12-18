package whelk.component

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import groovy.transform.Synchronized

import java.util.concurrent.*

import whelk.Document
import whelk.exception.*
import whelk.plugin.BasicPlugin
import whelk.plugin.Plugin
import whelk.Whelk

import whelk.util.Tools

import gov.loc.repository.pairtree.Pairtree

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.*
import org.apache.camel.*
import org.apache.camel.impl.*

import com.google.common.io.Files

import org.elasticsearch.action.search.*
import org.elasticsearch.search.sort.SortOrder
import static org.elasticsearch.index.query.QueryBuilders.*

class PairtreeHybridDiskStorage extends BasicElasticComponent implements HybridStorage {

    String indexName

    boolean rebuilding = false

    String baseStorageDir = "./storage"
    String storageDir = null
    String versionsStorageDir = null
    String baseStorageSuffix = null

    boolean versioning

    private ProducerTemplate producerTemplate

    final static Pairtree pairtree = new Pairtree()

    static final String ENTRY_FILE_NAME = "entry.json"
    static final String DATAFILE_EXTENSION = ".data"
    static final String MAIN_STORAGE_DIR = "main"
    static final String VERSIONS_STORAGE_DIR = "versions"
    static final String FILE_NAME_KEY = "dataFileName"

    static final String VERSION_DIR_PREFIX = "version_"

    static final Map FILE_EXTENSIONS = [
        "application/json" : ".json",
        "application/ld+json" : ".jsonld",
        "application/x-marc-json" : ".json",
        "application/xml" : ".xml",
        "text/xml" : ".xml"
    ]

    static Logger log = LoggerFactory.getLogger(PairtreeHybridDiskStorage.class)

    PairtreeHybridDiskStorage(Map settings) {
        super(settings)
        StringBuilder dn = new StringBuilder(settings['storageDir'])
        while (dn[dn.length()-1] == '/') {
            dn.deleteCharAt(dn.length()-1)
        }
        this.baseStorageDir = dn.toString()
        this.contentTypes = settings.get('contentTypes', null)
        this.versioning = settings.get('versioning', false)
        this.baseStorageSuffix = settings.get('baseStorageSuffix', null)
    }

    @Override
    void componentBootstrap(String stName) {
        if (!this.baseStorageSuffix) {
            this.baseStorageSuffix = this.id
        }
        if (versioning) {
            this.versionsStorageDir = this.baseStorageDir + "/" + stName + "_" + this.baseStorageSuffix + "/" + VERSIONS_STORAGE_DIR
        }
        this.storageDir = this.baseStorageDir + "/" + stName + "_" + this.baseStorageSuffix + "/" + MAIN_STORAGE_DIR
        this.indexName = "."+stName + "_" + this.baseStorageSuffix
    }

    @Override
    void onStart() {
        log.info("Starting ${this.id} with storageDir $storageDir ${(versioning ? "and versions in $versionsStorageDir" : "")}")
        createIndexIfNotExists(indexName)
        checkTypeMapping(indexName, METAENTRY_INDEX_TYPE)
    }

    @Override
    boolean eligibleForStoring(Document doc) {
        if (versioning) {
            Document currentDoc = get(doc.identifier)
            if (currentDoc && !currentDoc.isDeleted() && currentDoc.checksum == doc.checksum) {
                return false
            }
        }
        return true
    }

    @Override
    public boolean store(Document doc) {
        if (rebuilding) { throw new DownForMaintenanceException("The system is currently rebuilding it's indexes. Please try again later.") }
        boolean result = storeAsFile(doc)
        log.debug("Result from store-operation: $result")
        if (result) {
            index(doc.metadataAsJson.getBytes("utf-8"),
                [
                    "index": indexName,
                    "type": METAENTRY_INDEX_TYPE,
                    "id": toElasticId(doc.identifier)
                ]
            )
        }
        return result
    }

    @Override
    public void bulkStore(List<Document> docs) {
        if (rebuilding) { throw new DownForMaintenanceException("The system is currently rebuilding it's indexes. Please try again later.") }
        long startTime
        if (log.debugEnabled) {
            startTime = System.currentTimeMillis()
        }
        List<Map<String,String>> entries = []
        for (doc in docs) {
            boolean result = storeAsFile(doc)
            if (result) {
                entries << [
                "index":indexName,
                "type": METAENTRY_INDEX_TYPE,
                "id": toElasticId(doc.identifier),
                "data":((Document)doc).metadataAsJson
                ]
            }
        }
        log.trace("batchLoad() meantime after index prep ${System.currentTimeMillis() - startTime} milliseconds elapsed.")
        if (!entries.isEmpty()) {
            index(entries)
            log.trace("batchLoad() meantime after indexing ${System.currentTimeMillis() - startTime} milliseconds elapsed.")
            log.debug("batchLoad() completed in ${System.currentTimeMillis() - startTime} milliseconds.")
        }
    }

    @groovy.transform.CompileStatic
    boolean storeAsFile(Document doc) {
        if (doc) {
            if (this.contentTypes && !this.contentTypes.contains(doc.contentType)) {
                throw new WhelkAddException("[${this.id}] Not configured to handle documents of type ${doc.contentType}")
            }
            if (this.versioning) {
                try {
                    doc = checkAndUpdateExisting(doc)
                } catch (DocumentException de) {
                    if (de.exceptionType == DocumentException.IDENTICAL_DOCUMENT) {
                        log.debug("Identical document already in storage.")
                        return false
                    } else {
                        throw de
                    }
                }
            }
            String filePath = buildPath(doc.identifier)
            return writeDocumentToDisk(doc, filePath, getBaseFilename(doc.identifier))
        }
        return false
    }

    @groovy.transform.CompileStatic
    protected boolean writeDocumentToDisk(Document doc, String filePath, String fileName) {
        String extension = FILE_EXTENSIONS.get(doc.contentType, DATAFILE_EXTENSION)
        log.trace("Using extension: $extension")
        String sourcefilePath = filePath + "/" + fileName + extension
        File sourcefile = new File(sourcefilePath)
        File metafile = new File(filePath + "/" + ENTRY_FILE_NAME)
        try {
            log.trace("Saving file with path ${sourcefile.path}")
            FileUtils.writeByteArrayToFile(sourcefile, doc.data)
            log.trace("Setting entry in document meta")
            doc.getEntry().put(FILE_NAME_KEY, fileName + extension)
            log.trace("Saving file with path ${metafile.path}")
            FileUtils.write(metafile, doc.metadataAsJson, "utf-8")
            return true
        } catch (IOException ioe) {
            log.error("Write failed: ${ioe.message}", ioe)
            throw ioe
        }
        return false
    }

    Document checkAndUpdateExisting(Document doc) {
        log.trace("checking for existingdoc with identifier ${doc.identifier}")
        Document existingDocument = loadDocument(doc.identifier)
        log.trace("found: $existingDocument")
        int version = 1
        if (existingDocument) {
            if (existingDocument.entry?.checksum == doc.entry?.checksum) {
                throw new DocumentException(DocumentException.IDENTICAL_DOCUMENT, "Identical document already stored.")
            }
            version = existingDocument.version + 1
            Map versions = existingDocument.entry.versions ?: [:]
            String lastVersion = existingDocument.version as String
            versions[lastVersion] = [(Document.TIMESTAMP_KEY) : existingDocument.timestamp]
            if (existingDocument?.entry?.deleted) {
                versions.get(lastVersion).put("deleted", true)
            } else {
                versions.get(lastVersion).put("checksum",existingDocument.entry.checksum)
            }
            doc.entry.put("versions", versions)
            writeDocumentToDisk(existingDocument, buildPath(existingDocument.identifier, existingDocument.version), getBaseFilename(existingDocument.identifier))
        }
        log.trace("Setting document version: $version")
        return doc.withVersion(version)
    }

    @groovy.transform.CompileStatic
    String getBaseFilename(String identifier) {
        identifier.substring(identifier.lastIndexOf("/")+1)
    }


    Document get(String id, String version = null) {
        loadDocument(id, version)
    }

    @groovy.transform.CompileStatic
    private Document loadDocument(String uri, String version=null) {
        return loadDocument(new URI(uri), version)
    }

    private Document loadDocument(URI uri, String version = null) {
        log.debug("Received request for ${uri.toString()} with version $version")
        String filePath = buildPath(uri, (version ? version as int : 0))
        String fileName =  getBaseFilename(uri.toString())
        try {
            log.trace("filePath: $filePath")
            File metafile = new File(filePath + "/" + ENTRY_FILE_NAME)
            def metaEntry = mapper.readValue(FileUtils.readFileToString(metafile, "utf-8"), Map)
            def document = whelk.createDocument(metaEntry?.contentType).withMetaEntry(metaEntry)
            File sourcefile = new File(filePath + "/" + fileName + FILE_EXTENSIONS.get(document.contentType, DATAFILE_EXTENSION))
            return document.withData(FileUtils.readFileToByteArray(sourcefile))
        } catch (FileNotFoundException fnfe) {
            log.trace("Files on $filePath not found.")
            if (version) {
                log.debug("Trying to see if requested version is actually current version.")
                def document = loadDocument(uri)
                if (document && document.version == version as int) {
                    log.debug("Why yes, it was!")
                    return document
                } else {
                    log.debug("Nah, it wasn't")
                }
            }
            return null
        }
    }

    Iterable<Document> getAll(String dataset = null, Date since = null, Date until = null) {
        if (rebuilding) { throw new DownForMaintenanceException("The system is currently rebuilding it's indexes. Please try again later.") }
        if (dataset || since) {
            log.debug("Loading documents by index query for dataset $dataset ${(since ? "since $since": "")}")
            def elasticResultIterator = metaEntryQuery(dataset, since, until)
            return new Iterable<Document>() {
                Iterator<Document> iterator() {
                    def indexName = super.indexName
                    return new Iterator<Document>() {
                        public boolean hasNext() { elasticResultIterator.hasNext()}
                        public Document next() {
                            String nextIdentifier = elasticResultIterator.next()
                            Document nextDocument = loadDocument(nextIdentifier)
                            while (!nextDocument) {
                                super.log.warn("Document ${nextIdentifier} not found in storage. Removing it from index $indexName.")
                                deleteEntry(nextIdentifier, indexName, BasicElasticComponent.METAENTRY_INDEX_TYPE)
                                try {
                                    nextIdentifier = elasticResultIterator.next()
                                    nextDocument = loadDocument(nextIdentifier)
                                } catch (NoSuchElementException nse) {
                                    break
                                }
                            }
                            return nextDocument
                        }
                        public void remove() { throw new UnsupportedOperationException(); }
                    }
                }
            }
        }
        return getAllRaw(dataset)
    }

    @Override
    void remove(String id) {
        if (rebuilding) { throw new DownForMaintenanceException("The system is currently rebuilding it's indexes. Please try again later.") }

        if (versioning) {
            storeAsFile(createTombstone(id))
        } else {
            def fn = buildPath(id)
            log.debug("Deleting $fn")
            if (!new File(fn).deleteDir()) {
                log.error("Failed to delete $id")
                throw new WhelkRuntimeException("" + this.getClass().getName() + " failed to delete $id")
            }
        }
        deleteEntry(id, indexName, METAENTRY_INDEX_TYPE)
    }

    String buildPath(String id, int version = 0) {
        return buildPath(new URI(id), version)
    }

    String buildPath(URI uri, int version = 0) {
        String id = uri.toString()
        int pos = id.lastIndexOf("/")
        String path
        String baseDir = (version > 0 ? this.versionsStorageDir : this.storageDir)
        String encasingDir = (version > 0 ? VERSION_DIR_PREFIX + version : null)
        if (pos != -1) {
            path = pairtree.mapToPPath(baseDir + id.substring(0, pos), id.substring(pos+1), encasingDir)
        } else {
            path = pairtree.mapToPPath(baseDir, id, encasingDir)
        }
        return path
    }


    private Document createTombstone(String id) {
        def tombstone = whelk.createDocument("text/plain").withIdentifier(id).withData("DELETED ENTRY")
        tombstone.entry['deleted'] = true
        return tombstone
    }

    Iterable<Document> getAllRaw(String dataset = null) {
        File baseDir = (dataset != null ? new File(this.storageDir + "/" + dataset) : new File(this.storageDir))
        log.info("Starting reading for getAllRaw() at ${baseDir.getPath()}.")
        final Iterator<File> fileIterator = Files.fileTreeTraverser().preOrderTraversal(baseDir).iterator()
        return new Iterable<Document>() {
            Iterator<Document> iterator() {
                return new Iterator<Document>() {
                    static final Logger log = LoggerFactory.getLogger("whelk.component.PairtreeHybridDiskStorage")
                    File lastValidEntry = null

                    public boolean hasNext() {
                        while (fileIterator.hasNext()) {
                            File f = fileIterator.next()
                            if (f.name == PairtreeHybridDiskStorage.ENTRY_FILE_NAME) {
                                lastValidEntry = f
                                return true
                            }
                        }
                        return false
                    }

                    public Document next() {
                        if (lastValidEntry) {
                            def metaEntry = mapper.readValue(FileUtils.readFileToString(lastValidEntry, "utf-8"), Map)
                            def document = whelk.createDocument(metaEntry?.contentType).withMetaEntry(metaEntry)
                            try {
                                document.withData(FileUtils.readFileToByteArray(new File(lastValidEntry.getParentFile(), document.getEntry().get(PairtreeHybridDiskStorage.FILE_NAME_KEY))))
                            } catch (FileNotFoundException fnfe) {
                                log.trace("File not found using ${document.getEntry().get(PairtreeHybridDiskStorage.FILE_NAME_KEY)} as filename. Will try to use it as path.")
                                document.withData(FileUtils.readFileToByteArray(new File(document.getEntry().get(PairtreeHybridDiskStorage.FILE_NAME_KEY))))
                            } catch (InterruptedException e) {
                                e.printStackTrace()
                            }
                            lastValidEntry = null
                            return document
                        }
                        throw new NoSuchElementException()
                    }

                    public void remove() { throw new UnsupportedOperationException() }
                }
            }
        }
    }

    @Override
    void rebuildIndex() {
        rebuilding = true
        int diskCount = 0
        List<Map<String,String>> entryList = []
        log.info("Started rebuild of metaindex for $indexName.")
        createIndexIfNotExists(indexName)
        checkTypeMapping(indexName, METAENTRY_INDEX_TYPE)

        for (document in getAllRaw()) {
            entryList << [
            "index":indexName,
            "type": METAENTRY_INDEX_TYPE,
            "id": toElasticId(document.identifier),
            "data":((Document)document).metadataAsJson
            ]
            if (diskCount++ % 2000 == 0) {
                index(entryList)
                entryList = []
            }
            if (log.isInfoEnabled() && diskCount % 50000 == 0) {
                log.info("[${new Date()}] Rebuilding metaindex for $indexName. $diskCount sofar.")
            }
        }
        if (entryList.size() > 0) {
            index(entryList)
        }
        flush()
        log.info("Created $diskCount entries.")
        rebuilding = false
    }


    // Elastic stuff
    private SearchRequestBuilder buildMetaEntryQuery(String dataset, Date since, Date until, long lastTimestamp = -1) {
        def query = boolQuery()
        if (dataset) {
            query = query.must(termQuery("entry.dataset", dataset))
        }
        if (lastTimestamp < 0 && (since || until)) {
            def timeRangeQuery = rangeQuery("entry.timestamp")
            if (since) {
                timeRangeQuery = timeRangeQuery.from(since.getTime())
            }
            if (until) {
                timeRangeQuery = timeRangeQuery.to(until.getTime())
            }
            query = query.must(timeRangeQuery)
        }
        if (lastTimestamp >= 0) {
            def tsQuery = rangeQuery("entry.timestamp").gte(lastTimestamp)
            query = query.must(tsQuery)
        }
        def srb = client.prepareSearch(indexName)
            .setSearchType(SearchType.QUERY_THEN_FETCH)
            .setTypes([METAENTRY_INDEX_TYPE] as String[])
            .setFetchSource(["identifier", "entry.timestamp"] as String[], null)
            .setQuery(query)
            .setFrom(0).setSize(batchUpdateSize)
            .addSort("entry.timestamp", SortOrder.ASC)

        log.debug("MetaEntryQuery: $srb")

        return srb
    }

    Iterator<String> metaEntryQuery(String dataset, Date since, Date until) {
        LinkedHashSet<String> list = new LinkedHashSet<String>()
        long lastDocumentTimestamp = -1L

        return new Iterator<String>() {
            String lastLoadedIdentifier = null
            boolean listWasFull = true
            Iterator listIterator = null

            public boolean hasNext() {
                if (listWasFull && list.isEmpty()) {
                    listIterator = null
                    def srb = buildMetaEntryQuery(dataset, since, until, lastDocumentTimestamp)

                    def response = performExecute(srb)

                    String ident = null

                    for (hit in response.hits.hits) {
                        Map sourceMap = mapper.readValue(hit.source(), Map)
                        lastDocumentTimestamp = sourceMap.entry.get("timestamp", 0L)
                        ident = sourceMap.get("identifier")
                        list.add(ident)
                    }
                    if (lastLoadedIdentifier && lastLoadedIdentifier == ident) {
                        throw new WhelkRuntimeException("Got the identifier (${lastLoadedIdentifier}) again. Pulling the plug!! Maybe, you should try increasing the batchUpdateSize?")
                    }
                    lastLoadedIdentifier = ident
                    listWasFull = (list.size() >= super.batchUpdateSize)
                    super.log.debug("listWasFull: $listWasFull")
                    super.log.debug("list.size(): ${list.size()}")
                    listIterator = list.iterator()
                }
                return !list.isEmpty()
            }
            public String next() {
                if (listIterator == null) {
                    listIterator = list.iterator()
                }
                String n = listIterator.next()
                if (!listIterator.hasNext()) {
                    listIterator = null
                    list = new LinkedHashSet<String>()
                }
                return n
            }
            public void remove() { throw new UnsupportedOperationException(); }
        }
    }
}