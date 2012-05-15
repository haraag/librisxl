package se.kb.libris.conch.converter

import se.kb.libris.util.marc.Controlfield
import se.kb.libris.util.marc.MarcRecord
import se.kb.libris.util.marc.io.Iso2709MarcRecordReader

/**
 *
 * A mechanical transcription of {@link MarcRecord}s into JSON. The result is
 * compliant with the <a href="http://dilettantes.code4lib.org/blog/category/marc-in-json/">MARC-in-JSON</a> JSON schema.
 */
class MarcJSONConverter {

    static String toJSONString(MarcRecord record) {
        def builder = new groovy.json.JsonBuilder()
        builder {
            "leader"(record.leader)
            "fields"(record.fields.collect {[
                (it.tag): (it instanceof Controlfield)? (it.data) : [
                    ind1: it.getIndicator(0),
                    ind2:  it.getIndicator(1),
                    subfields: it.subfields.collect { [(it.code): it.data] }
                ]
            ]})
        }
        return builder.toPrettyString()
    }

    static void main(args) {
        MarcRecord record = new File(args[0]).withInputStream {
            new Iso2709MarcRecordReader(it/*, "utf-8"*/).readRecord()
        }
        println toJSONString(record)/*.replaceAll(
                /(?m)\{\s+(\S+: "[^"]+")\s+\}/, '{$1}')*/

    }

}
