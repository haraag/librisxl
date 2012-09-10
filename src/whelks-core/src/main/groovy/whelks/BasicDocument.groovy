package se.kb.libris.whelks.basic

import groovy.json.*
import groovy.util.logging.Slf4j as Log

import java.io.*
import java.net.URI
import java.util.*
import java.nio.ByteBuffer
import se.kb.libris.whelks.*
import se.kb.libris.whelks.exception.*

@Log
public class BasicDocument implements Document {
    URI identifier
    String version = "1", contentType 
    ByteArray data 
    Long size
    Set<Link> links = new TreeSet<Link>()
    Set<Key> keys = new TreeSet<Key>()
    Set<Tag> tags = new TreeSet<Tag>()
    Set<Description> descriptions = new TreeSet<Description>()
    Long timestamp = 0

    public BasicDocument() {
        this.timestamp = new Long(new Date().getTime())
    }

    BasicDocument(String jsonSource) {
        this.timestamp = new Long(new Date().getTime())
        log.debug("jsonSource: $jsonSource")
        fromMap(new JsonSlurper().parseText(jsonSource))
    }

    public Document fromMap(Map repr) {
        log.debug("repr: $repr")
        this.class.declaredFields.each {
            log.debug("field: ${it.name}")
            if (repr[it.name] && !it.isSynthetic()) {
                log.debug("value: " + repr[it.name])
                if (this.class.getDeclaredField(it.name).type.isAssignableFrom(repr[it.name].class)) {
                    log.debug("" + this.class.getDeclaredField(it.name).type + " == " + repr[it.name].class)
                    this.(it.name) = repr[it.name]
                } else {
                    log.debug("" + repr[it.name].class + " is not assignable")
                    try {
                        this.(it.name) = this.class.getDeclaredField(it.name).type.getConstructor(repr[it.name].class).newInstance(repr[it.name])
                    } catch (NoSuchMethodException n1) {
                        try {
                            this.(it.name) = this.class.getDeclaredField(it.name).type.getConstructor(String.class).newInstance(repr[it.name].toString())
                        } catch (NoSuchMethodException n2) {
                            log.error("Ultimate failure: ${n2.message}", n2)
                        }
                    }
                }
            }
        }
        return this
    }

    String toJson() {
        def json = [:]
        this.class.declaredFields.each {
            if (this.(it.name) && !it.isSynthetic() && !(it.getModifiers() & java.lang.reflect.Modifier.TRANSIENT)) {
                if (this.(it.name) instanceof URI) {
                    log.debug("found a URI identifier")
                    json.(it.name) = this.(it.name).toString()
                } else if (this.(it.name) instanceof ByteArray) {
                    log.debug("Found a bytearray")
                    json.(it.name) = this.(it.name).toList()
                } else {
                    json.(it.name) = this.(it.name)
                }
            }
        }
        /*
        json.identifier = identifier.toString()
        json.version = version
        json.contentType = contentType
        json.size = size
        json.links = links
        json.keys = keys
        json.tags = tags
        json.descriptions = descriptions
        json.timestamp = timestamp
        json.data = data.toList()
        */
        println "json: " +json

        return new JsonBuilder(json).toString()
    }

    @Override
    public byte[] getData() {
        return (data ? data.bytes : new byte[0])
    }

    @Override
    public byte[] getData(long offset, long length) {
        byte[] ret = new byte[(int)length]
        System.arraycopy(getData(), (int)offset, ret, 0, (int)length)

        return ret
    }

    /*
    public void setData(ByteArray _ba) {
        data = _ba
    }

    public void setData(byte[] _data) {
        data = new ByteArray(_data)
    }

    public void setData(InputStream data) {
        byte[] buf = new byte[1024]
        ByteArrayOutputStream bout = new ByteArrayOutputStream(1024)

        try {
            int n
            while ((n = data.read(buf)) != -1)
            bout.write(buf, 0, n)

            this.data = bout.toByteArray()
            this.size = this.data.length
        } catch (java.io.IOException e) {
            throw new WhelkRuntimeException("Error while reading from stream", e)
        }
    }
    */

    @Override
    public long getSize() {
        return (size ? size.longValue() : 0L)
    }

    @Override
    public Date getTimestampAsDate() {
        return new Date(timestamp)
    }

    @Override 
    public long getTimestamp() {
        return (timestamp ? timestamp.longValue() : 0L)
    }

    @Override
    public Document updateTimestamp() {
        timestamp = new Date().getTime()
        return this
    }

    public void setTimestamp(long _t) {
        this.timestamp = _t
    }

    public void setTimestamp(Date _timestamp) {
        if (_timestamp != null) {
            timestamp = new Long(_timestamp.getTime())
        }
    }

    /*
    @Override
    public Set<Link> getLinks() {
        return links
    }

    @Override
    public Set<Key> getKeys() {
        return keys
    }

    @Override
    public Set<Tag> getTags() {
        return tags
    }

    @Override
    public Set<Description> getDescriptions() {
        return descriptions
    }
    */

    @Override
    public Tag tag(URI type, String value) {
        synchronized (tags) {
            for (Tag t: tags)
            if (t.getType().equals(type) && t.getValue().equals(value))
                return t
        }
        BasicTag tag = new BasicTag(type, value)

        tags.add(tag)

        return tag
    }

    @Override
    public Document withData(String dataString) {
        return withData(dataString.getBytes())
    }

    @Override
    public Document withIdentifier(String uri) {
        try {
            this.identifier = new URI(uri)
        } catch (java.net.URISyntaxException e) {
            throw new WhelkRuntimeException(e)
        }
        return this
    }

    @Override
    public Document withIdentifier(URI uri) {
        this.identifier = uri
        return this
    }

    @Override
    public Document withData(byte[] data) {
        this.data = new ByteArray(data)
        this.size = data.length
        return this
    }

    @Override
    public Document withContentType(String contentType) {
        this.contentType = contentType
        return this
    }

    @Override
    public Document withSize(long size) {
        this.size = size
        return this
    }

    @Override
    public String getDataAsString() {
        return new String(getData())
    }

    @Override
    public InputStream getDataAsStream() {
        return new ByteArrayInputStream(getData())
    }

    public Map getDataAsJsonMap() {

    }


    @Override
    public InputStream getDataAsStream(long offset, long length) {
        return new ByteArrayInputStream(getData(), (int)offset, (int)length)
    }

    @Override
    public void untag(URI type, String value) {
        synchronized (tags) {
            Set<Tag> remove = new HashSet<Tag>()

            for (Tag t: tags)
            if (t.getType().equals(type) && t.getValue().equals(value))
                remove.add(t)

            tags.removeAll(remove)
        }
    }
}

class ByteArray {

    byte[] bytes

    ByteArray(String str) {
        bytes = str.getBytes()
    }

    ByteArray(byte[] b) {
        bytes = b
    }

    ByteArray(ArrayList al) {
        bytes = new byte[al.size()]
        int i = 0
        al.each {
            bytes[i++] = it
        }
    }

    List toList() {
        def l = []
        l.addAll(0, bytes)
        return l
    }

    @Override
    public String toString() {
        return new String(bytes)
    }
}

class HighlightedDocument extends BasicDocument {
    Map<String, String[]> matches = new TreeMap<String, String[]>()

    HighlightedDocument(Document d, Map<String, String[]> match) {
        withData(d.getData()).withIdentifier(d.identifier).withContentType(d.contentType)
        this.matches = match
    }

    @Override
    String getDataAsString() {
        def slurper = new JsonSlurper()
        def json = slurper.parseText(super.getDataAsString())
        json.highlight = matches
        def builder = new JsonBuilder(json)
        return builder.toString()
    } 
}
