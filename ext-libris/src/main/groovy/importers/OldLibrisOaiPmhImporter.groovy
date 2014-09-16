package se.kb.libris.whelks.importers

import groovy.xml.StreamingMarkupBuilder
import groovy.util.slurpersupport.GPathResult
import groovy.util.logging.Slf4j as Log

import java.text.*
import java.util.concurrent.*

import se.kb.libris.whelks.*
import se.kb.libris.whelks.exception.*
import se.kb.libris.util.marc.*
import se.kb.libris.util.marc.io.*
import se.kb.libris.whelks.plugin.*
import se.kb.libris.conch.converter.MarcJSONConverter
import se.kb.libris.conch.Tools

@Log
class OldOAIPMHImporter extends BasicPlugin implements Importer {

    static SERVICE_BASE_URL = "http://data.libris.kb.se/{dataset}/oaipmh"

    Whelk whelk
    String dataset

    String serviceUrl
    int recordCount = 0
    int nrDeleted = 0
    long startTime = 0

    boolean picky = true
    boolean silent = false
    boolean preserveTimestamps = true

    long runningTime = 0

    def specUriMapping = [:]

    ExecutorService queue
    Semaphore tickets
    int numberOfThreads = 10000
    MarcFrameConverter marcFrameConverter
    JsonLDLinkCompleterFilter enhancer

    boolean cancelled = false

    List errorMessages = []

    OldOAIPMHImporter(Map settings) {
        this.serviceUrl = settings.get('serviceUrl',SERVICE_BASE_URL)
        this.preserveTimestamps = settings.get("preserveTimestamps", true)
        this.specUriMapping = settings.get("specUriMapping", [:])
        this.numberOfThreads = settings.get("numberOfThreads", 50000)
    }

    void bootstrap(String whelkId) {
        marcFrameConverter = plugins.find { it instanceof MarcFrameConverter }
        enhancer = plugins.find { it instanceof JsonLDLinkCompleterFilter }
    }

    int doImport(String dataset, String startResumptionToken = null, int nrOfDocs = -1, boolean silent = false, boolean picky = true, Date from = null) {
        getAuthentication()
        this.cancelled = false
        this.dataset = dataset
        this.picky = picky
        this.silent = silent
        this.recordCount = 0
        this.nrDeleted = 0
        String baseUrl = serviceUrl.replace("{dataset}", dataset)

        String urlString = baseUrl + "?verb=ListRecords&metadataPrefix=marcxml"

        def versioningSettings = [:]

        tickets = new Semaphore(numberOfThreads)

        if (from) {
            urlString = urlString + "&from=" + from.format("yyyy-MM-dd'T'HH:mm:ss'Z'")
        } else {
            for (st in this.whelk.getStorages()) {
                log.info("Turning off versioning in ${st.id}")
                // Preserve original setting
                versioningSettings.put(st.id, st.versioning)
                st.versioning = false
            }
        }
        queue = Executors.newSingleThreadExecutor()
        startTime = System.currentTimeMillis()
        URL url
        if (startResumptionToken) {
            url = new URL(baseUrl + "?verb=ListRecords&resumptionToken=" + startResumptionToken)
            log.info("Harvesting OAIPMH data from ${url.toString()}. Pickymode: $picky")
        } else {
            url = new URL(urlString)
            log.info("Harvesting OAIPMH data from $urlString. Pickymode: $picky")
        }
        String resumptionToken = harvest(url)
        log.debug("resumptionToken: $resumptionToken")
        long loadUrlTime = startTime
        long elapsed = 0
        while (!cancelled && resumptionToken && (nrOfDocs == -1 || recordCount <  nrOfDocs)) {
            loadUrlTime = System.currentTimeMillis()
            url = new URL(baseUrl + "?verb=ListRecords&resumptionToken=" + resumptionToken)
            log.trace("Harvesting $url")
            try {
                String rtok = harvest(url)
                resumptionToken = rtok
            } catch (XmlParsingFailedException xpfe) {
                log.warn("Harvesting failed. Retrying ...")
            }
            elapsed = System.currentTimeMillis() - loadUrlTime
            if (elapsed > 6000) {
                log.warn("Harvest took more than 3 seconds ($elapsed)")
            }
            log.debug("resumptionToken: $resumptionToken")
        }
        log.info("Flushing data ...")
        queue.execute({
            this.whelk.flush()
            log.info("Resetting versioning setting for storages")
            if (!from) {
                for (st in this.whelk.getStorages()) {
                    st.versioning = versioningSettings.get(st.id)
                }
            }
        } as Runnable)
        log.debug("Shutting down queue")
        queue.shutdown()
        return recordCount
    }


    String washXmlOfBadCharacters(String xmlString) {
        log.warn("Trying to recuperate by washing XML ...")
        StringBuilder sb = new StringBuilder(xmlString)
        for (int i=0;i<sb.length();i++)
            if (sb.charAt(i) < 0x09 || (sb.charAt(i) > 0x0D && sb.charAt(i) < 0x1F)) {
                log.warn("Found illegal character: ${sb.charAt(i)}")
                sb.setCharAt(i, '?' as char);
            }

        return sb.toString()
    }

    String harvest(URL url) {
        long elapsed = System.currentTimeMillis()
        def xmlString = normalizeString(url.text)
        if ((System.currentTimeMillis() - elapsed) > 5000) {
            log.warn("Load from URL ${url.toString()} took more than 5 seconds (${System.currentTimeMillis() - elapsed})")
        }
        def OAIPMH
        elapsed = System.currentTimeMillis()
        try {
            OAIPMH = new XmlSlurper(false,false).parseText(xmlString)
        } catch (org.xml.sax.SAXParseException spe) {
            if (xmlString != null && xmlString.length() > 0) {
                xmlString = washXmlOfBadCharacters(xmlString)
                try {
                    OAIPMH = new XmlSlurper(false,false).parseText(xmlString)
                } catch (org.xml.sax.SAXParseException sp) {
                    log.error("Failed to parse XML despite efforts to clean: $xmlString", sp)
                    throw new XmlParsingFailedException("Failing XML: ($xmlString)", sp)
                }
            } else {
                log.error("Failed to parse XML: $xmlString", spe)
                throw new XmlParsingFailedException("Failing XML: ($xmlString)", spe)
            }
        }
        if ((System.currentTimeMillis() - elapsed) > 1000) {
            log.warn("XML slurping took more than 1 second (${System.currentTimeMillis() - elapsed})")
        }
        def documents = []
        def marcdocuments = []
        elapsed = System.currentTimeMillis()
        OAIPMH.ListRecords.record.each {
            String mdrecord = createString(it.metadata.record)
            if (mdrecord) {
                try {
                    MarcRecord record = MarcXmlRecordReader.fromXml(mdrecord)

                    def entry = ["identifier":"/"+this.dataset+"/"+record.getControlfields("001").get(0).getData(),"dataset":this.dataset]

                    if (preserveTimestamps && it.header.datestamp) {
                        def date = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", it.header.datestamp.toString())
                        log.trace("Setting date: $date")
                        entry.put(Document.MODIFIED_KEY, date.getTime())
                    }

                    def meta = [:]

                    if (it.header.setSpec) {
                        for (spec in it.header.setSpec) {
                            for (key in specUriMapping.keySet()) {
                                if (spec.toString().startsWith(key+":")) {
                                    String link = new String("/"+(specUriMapping[spec.toString().split(":")[0]] ?: spec.toString().split(":")[0])+"/" + spec.toString().split(":")[1])

                                    meta.get("link", []).add(link)
                                }
                            }
                        }
                    }

                    try {
                        if (marcFrameConverter) {
                            if (enhancer) {
                                documents << enhancer.filter(marcFrameConverter.doConvert(record, ["entry":entry,"meta":meta]))
                            } else {
                                documents << marcFrameConverter.doConvert(record, ["entry":entry,"meta":meta])
                            }
                        }
                        def marcmeta = meta
                        marcmeta.put("oaipmh_header", createString(it.header))
                        marcdocuments << new Document(["entry":entry, "meta":marcmeta]).withData(mdrecord).withContentType("application/marcxml+xml")
                    } catch (Exception e) {
                        log.error("Conversion failed for id ${entry.identifier}", e)
                    }
                    recordCount++
                    def velocityMsg = ""
                    runningTime = System.currentTimeMillis() - startTime
                    if (!silent) {
                        velocityMsg = "Current velocity: ${recordCount/(runningTime/1000)}."
                        Tools.printSpinner("Running OAIPMH ${this.dataset} import. ${recordCount} documents imported sofar. $velocityMsg", recordCount)
                    }
                } catch (Exception e) {

                    log.error("Failed! (${e.message}) for :\n$mdrecord", e)
                    if (picky) {
                        log.error("Picky mode enabled. Throwing exception", e)
                        throw e
                    }
                }
            } else if (it.header.@deleted == 'true') {
                String deleteIdentifier = "/" + new URI(it.header.identifier.text()).getPath().split("/")[2 .. -1].join("/")
                    try {
                        whelk.remove(new URI(deleteIdentifier))
                    } catch (Exception e2) {
                        log.error("Whelk remove of $deleteIdentifier triggered exception.", e2)
                    }
                nrDeleted++
            } else {
                throw new WhelkRuntimeException("Failed to handle record: " + createString(it))
            }
        }
        if ((System.currentTimeMillis() - elapsed) > 3000) {
            log.warn("Conversion of documents took more than 3 seconds (${System.currentTimeMillis() - elapsed})")
        }
        if (documents?.size() > 0) {
            addDocuments(documents)
        }
        if (marcdocuments?.size() > 0) {
            addDocuments(marcdocuments)
        }

        if (!OAIPMH.ListRecords.resumptionToken.text()) {
            log.trace("Last page is $xmlString")
        }
        return OAIPMH.ListRecords.resumptionToken

    }

    void addDocuments(final List documents) {
        if (tickets.availablePermits() < 10) {
            log.info("Trying to acquire semaphore for adding to queue. ${tickets.availablePermits()} available.")
        }
        tickets.acquire()
        queue.execute({
            try {
                log.debug("Adding ${documents.size()} documents to whelk.")
                long elapsed = System.currentTimeMillis()
                //def storage = whelk.getStorage(document.get(0).contentType)
                //storage.bulkStore(documents)
                this.whelk.bulkAdd(documents, documents.get(0).contentType)
                if ((System.currentTimeMillis() - elapsed) > 3000) {
                    log.warn("Bulk add took more than 3 seconds (${System.currentTimeMillis() - elapsed})")
                }
            } catch (WhelkAddException wae) {
                log.warn("Failed adding: ${wae.message} (${wae.failedIdentifiers})")
                throw wae
            } catch (Exception e) {
                log.error("Exception on bulkAdd: ${e.message}", e)
                throw e
            } finally {
                tickets.release()
            }
        } as Runnable)
    }

    private void getAuthentication() {
        try {
            Properties properties = new Properties()
            properties.load(this.getClass().getClassLoader().getResourceAsStream("oaipmh.properties"))
            final String username = properties.getProperty("username")
            final String password = properties.getProperty("password")
            Authenticator.setDefault(new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password.toCharArray())
                    }
                });
        } catch (Exception ex) {
            log.error("Exception getting authentication credentials: $ex")
        }
    }

    String normalizeString(String inString) {
        if (!Normalizer.isNormalized(inString, Normalizer.Form.NFC)) {
            log.trace("Normalizing ...")
            return Normalizer.normalize(inString, Normalizer.Form.NFC)
        }
        return inString
    }

    String createString(GPathResult root) {
        return new StreamingMarkupBuilder().bind{
            out << root
        }
    }

    void cancel() {
        this.cancelled = true
    }
}

class XmlParsingFailedException extends Exception {
    XmlParsingFailedException() {
        super("Parse failed. Most likely, the received document was empty. Or null.")
    }
    XmlParsingFailedException(String msg) {
        super(msg)
    }
    XmlParsingFailedException(Throwable t) {
        super("Parse failed. Most likely, the received document was empty. Or null.", t)
    }
    XmlParsingFailedException(String msg, Throwable t) {
        super(msg, t)
    }

}