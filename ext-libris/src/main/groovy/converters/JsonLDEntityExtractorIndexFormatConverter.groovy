package se.kb.libris.whelks.plugin

import se.kb.libris.whelks.*

import static se.kb.libris.conch.Tools.*

import groovy.util.logging.Slf4j as Log
import org.codehaus.jackson.map.ObjectMapper

@Log
class JsonLDEntityExtractorIndexFormatConverter extends BasicFormatConverter {

    String requiredContentType = "application/ld+json"
    String resultContentType = "application/json"
    ObjectMapper mapper = new ObjectMapper()
    def authPoint = ["Person": "controlledLabel", "Concept": "prefLabel", "ConceptScheme": "notation", "Organization": "name", "Work": "uniformTitle"]
    def entitiesToExtract = ["about.inScheme", "about.instanceOf.attributedTo", "about.instanceOf.influencedBy", "about.attributedTo"]

    Document doConvert(Document doc) {
        log.debug("Converting indexdoc $doc.identifier")

        def docType = new URI(doc.identifier).path.split("/")[1]

        List<Map> doclist = [["id":doc.identifier, "entity":doc.dataAsMap, "contentType":doc.contentType]]

        def json = getDataAsMap(doc)

        if (json) {

            if (json.about?.get("@type")) {
                if (authPoint.containsKey(json.about.get("@type"))) {
                    log.debug("Extracting authority entity " + json.about.get("@type"))
                    /*def slugId = false
                    if (json.about.get("@type").equals("ConceptScheme")) {
                        slugId = true
                    } */
                    doclist << createEntityDoc(json.about, doc.identifier, 10, false)
                }
            }

            for (it in entitiesToExtract) {
                def jsonMap = json
                def propList = it.tokenize(".")
                for (prop in propList) {
                    try {
                        jsonMap = jsonMap[prop]
                    } catch (Exception e) {
                        jsonMap = null
                        break
                    }
                }
                if (jsonMap) {
                    log.debug("Extracting entity $it")
                    doclist.addAll(extractEntities(jsonMap, doc.identifier, docType, 1))
                }
            }
        }
        return new Document().withData(["extracted_entities": doclist]).withContentType(resultContentType)
    }

    List<Map> extractEntities(extractedJson, id, String type, prio) {
        List<Map> entityDocList = []
        if (extractedJson instanceof List) {
            for (entity in extractedJson) {
                if (!(type.equals("bib") && entity.get("@id")) && !type.equals("hold")) {  //only extract bib-entity that doesn't link to existing authority and don't extract hold-entities
                    def entityDoc = createEntityDoc(entity, id, prio, true)
                    if (entityDoc) {
                        entityDocList << entityDoc
                    }
                }
            }
        } else if (extractedJson instanceof Map) {
            if (!(type.equals("bib") && extractedJson.get("@id")) && !type.equals("hold")) {
                def entityDoc = createEntityDoc(extractedJson, id, prio, true)
                if (entityDoc) {
                    entityDocList << entityDoc
                }
            }
        }
        return entityDocList
    }

    Map createEntityDoc(def entityJson, def docId, def prio, def slugifyId) {
        try {
            def indexId = entityJson["@id"]
            def type = entityJson["@type"]
            if (slugifyId) {
                def label = authPoint.get(type, null)
                def authPath = entityJson[label]
                if (!label) {
                    log.debug("Type $type not declared for index entity extraction.")
                    return null
                }
                indexId = slugify(authPath, new URI(docId), type)
            }
            entityJson["extractedFrom"] = ["@id": docId]
            entityJson["recordPriority"] = prio
            entityJson.get("unknown", null) ?: entityJson.remove("unknown")
            log.debug("Created indexdoc ${indexId} with prio $prio")
            return ["id":indexId, "dataset":type, "entity" : entityJson, "contentType":"application/ld+json"]
            //def d = new Document().withEntry(["dataset":type,"origin":docId]).withData(mapper.writeValueAsBytes(entityJson)).withContentType("application/ld+json").withIdentifier(indexId)
            //return d
        } catch (Exception e) {
            log.debug("Could not create entitydoc ${e} from docId: $docId" + " EntityJson " + mapper.writeValueAsString(entityJson))
            return null
        }
    }

    String slugify(String authAccPoint, URI identifier, String entityType) {
        String uritype = identifier.path.split("/")[1]
        return new String("/" + uritype + "/" + entityType + "/" + URLEncoder.encode(authAccPoint))
    }
}
