package se.kb.libris.whelks.api
import groovy.util.logging.Slf4j as Log
import javax.servlet.http.*

import javax.activation.MimetypesFileTypeMap

import se.kb.libris.conch.Tools
import se.kb.libris.whelks.*
import se.kb.libris.whelks.component.*
import se.kb.libris.whelks.plugin.*
import se.kb.libris.whelks.exception.*

@Log
class DocumentAPI extends BasicAPI {
    MimetypesFileTypeMap mt = new MimetypesFileTypeMap()

    String description = "A GET request with identifier loads a document. A PUT request stores a document. A DELETE request deletes a document."
        Map contextHeaders = [:]
        DocumentAPI(Map settings) {
            this.contextHeaders = settings.get("contextHeaders", [:])
        }
    def determineDisplayMode(path) {
        if (path.endsWith("/meta")) {
            return [path[0 .. -6], DisplayMode.META]
        }
        return [path, DisplayMode.DOCUMENT]
    }
    String getCleanPath(List pathVars) {
        if (pathVars) {
            return "/"+pathVars.first().replaceAll('\\/\\/', '/')
        }
        return "/"
    }
    protected void doHandle(HttpServletRequest request, HttpServletResponse response, List pathVars) {
        String path = getCleanPath(pathVars)
        log.debug "Path: $path"
        if (request.method == "GET") {
            handleGetRequest(request, response, path)
        } else if (request.method == "POST") {
            log.debug("POST detected.")
            int pathsize = path.split("/").size()
            if (pathsize == 0 || pathsize == 2) {
                handlePutAndPostRequest(request, response, path, false)
            } else {
                response.sendError(response.SC_BAD_REQUEST, "POST only allowed to root or dataset")
            }
        } else if (request.getMethod() == "PUT") {
            handlePutAndPostRequest(request, response, path, true)
        }
        else if (request.method == "DELETE") {
            try {
                log.debug("Removing resource at $path")
                whelk.remove(new URI(path))
                response.setStatus(HttpServletResponse.SC_NO_CONTENT)
            } catch (WhelkRuntimeException wre) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, wre.message)
            }
        }
    }


    void handleGetRequest(HttpServletRequest request, HttpServletResponse response, String path) {
        def mode = DisplayMode.DOCUMENT
        (path, mode) = determineDisplayMode(path)
        def version = request.getParameter("version")
        def accepting = request.getHeader("accept")?.split(",").collect {
            int last = (it.indexOf(';') == -1 ? it.length() : it.indexOf(';'))
            it.substring(0,last)
        }
        log.debug("Accepting $accepting")
        try {
            def d = null
            if (version) {
                d = whelk.get(new URI(path), version, accepting)
            } else {
                def location = whelk.locate(new URI(path))
                d = location?.document
                if (!d && location?.uri) {
                    def locationRef = request.getScheme() + "//" + request.getServerName() + (request.getServerPort() != 80 ? ":" + request.getServerPort() : "") + request.getContextPath()
                    response.setHeader("Location", locationRef + location.uri.toString())
                    sendResponse(response, null, null, location.responseCode)
                    return
                }
            }
            def extensionContentType = (mt.getContentType(path) == "application/octet-stream" ? null : mt.getContentType(path))
            log.info("mimetype: $extensionContentType")
            if (path ==~ /(.*\.\w+)/) {
                log.info("Found extension in $path")
                if (!d && extensionContentType) {
                    d = whelk.get(new URI(path.substring(0, path.lastIndexOf("."))))
                }
                accepting = [extensionContentType]
            }
            if (d && accepting && !accepting.contains("*/*") && !accepting.contains(d.contentType) && !accepting.contains(getMajorContentType(d.contentType))) {
                def fc = plugins.find { it instanceof FormatConverter && accepting.contains(it.resultContentType) && it.requiredContentType == d.contentType }
                if (fc) {
                    log.info("Found formatconverter for ${fc.resultContentType}")
                    d = fc.convert(d)
                    if (extensionContentType) {
                        response.setHeader("Content-Location", path)
                    }
                } else {
                    d = null
                }
            }

            if (d && (mode== DisplayMode.META || !d.entry['deleted'])) {

                for (filter in getFiltersFor(d)) {
                    log.debug("Filtering using ${filter.id} for ${d.identifier}")
                        d = filter.filter(d)
                }
                if (mode == DisplayMode.META) {
                    sendResponse(response, d.metadataAsJson, "application/json")
                } else {
                    def ctheader = contextHeaders.get(path.split("/")[1])
                    if (ctheader) {
                        response.setHeader("Link", "<$ctheader>; rel=\"http://www.w3.org/ns/json-ld#context\"; type=\"application/ld+json\"")
                    }
                    response.setHeader("ETag", d.timestamp as String)
                    def contentType = getMajorContentType(d.contentType)
                    if (path in contextHeaders.collect { it.value })  {
                        contentType = d.contentType
                        log.debug("request is for context file. Must serve original content-type ($contentType).")
                    }
                    sendResponse(response, d.dataAsString, contentType)
                }
            } else {
                log.debug("Failed to find a document with URI $path")
                response.sendError(response.SC_NOT_FOUND)
            }
        } catch (WhelkRuntimeException wrte) {
            response.sendError(response.SC_INTERNAL_SERVER_ERROR, wrte.message)
        }
    }

    void handlePutAndPostRequest(HttpServletRequest request, HttpServletResponse response, String path, boolean identifierSupplied) {
        log.debug("PATH: $path")
        try {
            if (path == "/") {
                throw new WhelkRuntimeException("PUT requires a proper URI.")
            }
            def entry = [
            "contentType":request.getContentType(),
            "dataset":getDatasetBasedOnPath(path)
            ]
            if (identifierSupplied) {
                entry['identifier'] = path
                    // Check If-Match
                    String ifMatch = request.getHeader("If-Match")
                    if (ifMatch && this.whelk.get(new URI(path)) && this.whelk.get(new URI(path))?.timestamp as String != ifMatch) {
                        response.sendError(response.SC_PRECONDITION_FAILED, "The resource has been updated by someone else. Please refetch.")
                            return
                    }
            }

            try {
                Document doc = new Document(["entry":entry,"meta":request.getParameterMap()]).withData(request.getInputStream().getBytes())

                def identifier = convertAndSaveDocument(doc)
                def locationRef = request.getRequestURL()

                if (!identifierSupplied) {
                    while (locationRef[-1] == '/') {
                        locationRef.deleteCharAt(locationRef.length()-1)
                    }

                    if (entry['dataset'] && locationRef.toString().endsWith(entry['dataset'])) {
                        int endPos = locationRef.length()
                        int startPos = endPos - entry['dataset'].length() - 1
                        locationRef.delete(startPos, endPos)
                    }

                    locationRef.append(identifier)

                }

                sendDocumentSavedResponse(response, locationRef.toString(), doc.timestamp as String)

            } catch (DocumentException de) {
                log.warn("Document exception: ${de.message}")
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, de.message)
            } catch (WhelkAddException wae) {
                log.warn("Whelk failed to store document: ${wae.message}")
                response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE , wae.message)
            }
        } catch (WhelkRuntimeException wre) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST, wre.message)
        }
    }

    URI convertAndSaveDocument(Document doc) {
        doc = this.whelk.sanityCheck(doc)
        log.debug("Saving document first pass. (${doc.identifier})")
        def identifier = this.whelk.add(doc)
        for (fc in plugins.findAll { it instanceof FormatConverter && it.requiredContentType == doc.contentType }) {
            try {
                log.debug("Running formatconverter ${fc.id} on ${doc.identifier}")
                    doc = fc.convert(doc)
                    doc = this.whelk.sanityCheck(doc)
                    identifier = this.whelk.add(doc)
            } catch (WhelkAddException wae) {
                log.warn("Converted to ${doc.contentType} but there are no storages for that.")
            }
        }
        return identifier
    }


    void sendDocumentSavedResponse(HttpServletResponse response, String locationRef, String timestamp) {
        response.setHeader("Location", locationRef)
        response.setHeader("ETag", timestamp as String)
        response.setStatus(HttpServletResponse.SC_CREATED, "Thank you! Document ingested.")
    }

    private String getDatasetBasedOnPath(path) {
        String ds = ""
        def elements = path.split("/")
        int i = 1
        while (ds.length() == 0 || ds == whelk.id) {
            ds = elements[i++]
        }
        return ds
    }

    List<Filter> getFiltersFor(Document doc) { return plugins.findAll { it instanceof Filter && it.valid(doc) } }
}

enum DisplayMode {
DOCUMENT, META
}
