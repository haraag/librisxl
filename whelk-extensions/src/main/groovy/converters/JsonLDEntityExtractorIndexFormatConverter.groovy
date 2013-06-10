package se.kb.libris.whelks.plugin

import se.kb.libris.whelks.*
import se.kb.libris.whelks.basic.*

import groovy.util.logging.Slf4j as Log
import org.codehaus.jackson.map.ObjectMapper

@Log
class JsonLDEntityExtractorIndexFormatConverter extends BasicIndexFormatConverter implements IndexFormatConverter {

    String requiredContentType = "application/ld+json"
    ObjectMapper mapper = new ObjectMapper()
    
    List<Document> doConvert(Document doc) {
        def doclist = [doc]
        def json = mapper.readValue(doc.dataAsString, Map)
        def indexTypeLists = ["person": json.about?.instanceOf?.authorList, "concept": json.about?.instanceOf?.subject]
        def entityLinks = ["person": "authorOf", "concept": "subject"]
        indexTypeLists.each { k, entities ->
            if (entities) {
                if (entities instanceof List) {
                    for (entity in entities) {
                        if (entity.get("authorizedAccessPoint", null)) {
                            doclist << createEntityDoc(k, entity, doc.identifier, entityLinks[(k)])
                        }
                    }
                } else if (entities instanceof Map) {
                    if (entities.get("authorizedAccessPoint", null)) {
                        doclist << createEntityDoc(k, entities, doc.identifier, entityLinks[(k)])
                    }
                }
            }
        }
        if (json["@type"]) {
            log.debug("Record has a @type. Adding to entity recordtype.")
            json["recordPriority"] = 1
            json.remove("unknown")
            doclist << new Document().withData(mapper.writeValueAsBytes(json)).withContentType("application/ld+json").withIdentifier(doc.identifier).tag("entityType", json["@type"])
        }
        if (json.about?.instanceOf?.subjectList) {
            json.about.instanceOf.subjectList.each {
                if (it.get("term", null)) {
                    doclist << createEntityDoc("concept", it, doc.identifier, entityLinks["concept"])
                }
            }
        }
        log.debug("Extraction results: $doclist")
        return doclist
    }

    Document createEntityDoc(def type, def entityJson, def docId, def linkType) {
        String pident
        if (entityJson.get("authorizedAccessPoint", null)) {
            pident = slugify(entityJson.get("authorizedAccessPoint"), docId, type)
        } else if (entityJson.get("term", null)) {
            pident = slugify(entityJson.get("term"), docId, type)
        }
        entityJson["@id"] = pident
        entityJson["recordPriority"] = 0
        return new Document().withData(mapper.writeValueAsBytes(entityJson)).withContentType("application/ld+json").withIdentifier(pident).tag("entityType", type).withLink(docId, linkType)
    }

    String slugify(String authAccPoint, URI identifier, String entityType) {
        String uritype = identifier.path.split("/")[1]
        return new String("/" + uritype + "/" + entityType + "/" + URLEncoder.encode(authAccPoint))
    }
}
