package whelk.converter.marc

import groovy.util.logging.Slf4j as Log
import org.codehaus.jackson.map.ObjectMapper
import se.kb.libris.util.marc.MarcRecord
import whelk.Document
import whelk.JsonLd
import whelk.converter.FormatConverter
import whelk.converter.JSONMarcConverter

@Log
class JsonLD2MarcXMLConverter implements FormatConverter {

    JsonLD2MarcConverter jsonldConverter = null
    final static ObjectMapper mapper = new ObjectMapper()

    JsonLD2MarcXMLConverter() {
        jsonldConverter = new JsonLD2MarcConverter()
    }

    @Override
    Document convert(final Document doc) {

        assert (doc instanceof  Document)

        doc.withData(JsonLd.frame(doc.id, doc.data))

        Document marcJsonDocument = jsonldConverter.convert(doc)

        MarcRecord record = JSONMarcConverter.fromJson(marcJsonDocument.getDataAsString())

        log.debug("Setting document identifier in field 887.")
        boolean has887Field = false
        for (field in record.getDatafields("887")) {
            if (!field.getSubfields("2").isEmpty() && field.getSubfields("2").first().data == "librisxl") {
                has887Field = true
                def subFieldA = field.getSubfields("a").first()
                subFieldA.setData(mapper.writeValueAsString(["@id":doc.identifier,"modified":doc.modified,"checksum":doc.checksum]))
            }
        }
        if (!has887Field) {
            def df = record.createDatafield("887")
            df.addSubfield("a".charAt(0), mapper.writeValueAsString(["@id":doc.identifier,"modified":doc.modified,"checksum":doc.checksum]))
            df.addSubfield("2".charAt(0), "librisxl")
            record.addField(df)
        }

        Document xmlDocument = new Document(doc.id, [(Document.NON_JSON_CONTENT_KEY): whelk.converter.JSONMarcConverter.marcRecordAsXMLString(record)], doc.manifest).withContentType(getResultContentType())

        log.debug("Document ${xmlDocument.identifier} created successfully with entry: ${xmlDocument.manifest}")
        return xmlDocument
    }

    @Override
    String getRequiredContentType() {
        return "application/ld+json"
    }

    @Override
    String getResultContentType() {
        return "application/marcxml+xml"
    }
}
