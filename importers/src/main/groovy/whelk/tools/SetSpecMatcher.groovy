package whelk.tools

/**
 * Created by Theodor on 2016-11-21.
 */
class SetSpecMatcher {

    //static List auhtLinkableFieldNames = ['100', '600', '700', '800', '110', '610', '710', '810', '130', '630', '730', '830', '650', '651', '655']
    static List ignoredAuthFields = ['180', '181', '182', '185', '162']
    //TODO: franska diakriter

    static Map fieldRules = [
            '100': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['100', '600', '700', '800']],
            '110': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['110', '610', '710']],
            '111': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['111', '611', '711']],
            '130': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['130', '630', '730', '830']],
            '150': [subFieldsToIgnore: [bib: ['0', '2', '4'], auth: ['6']],
                    bibFields        : ['650']],
            '151': [subFieldsToIgnore: [bib: ['0', '2', '4'], auth: ['6']],
                    bibFields        : ['651']],
            '155': [subFieldsToIgnore: [bib: ['0', '2', '4'], auth: ['6']],
                    bibFields        : ['655']]
    ]


    static void matchAuthToBib(Map doc, List setSpecs) {
        List matchedFields = []
        List misMatchedFields = []

        //Prepare setspec groups
        if (setSpecs.any()) {
            setSpecs.each { spec ->
                spec.put("normalizedSubfields",
                        normaliseSubfields(spec.subfields)
                                .findAll { it -> !fieldRules[spec.field].subFieldsToIgnore.auth.contains(it.keySet()[0]) }
                                .collect { it -> it })
            }
            def specGroups = setSpecs.findAll { it -> !ignoredAuthFields.contains(it.field) }
                    .groupBy { it.field }
                    .toSorted {
                -(it.hasProperty('normalizedSubfields') ? it.normalizedSubfields.count {
                    it
                } : 0)
            }

            //Prepare bib field groups
            List<String> auhtLinkableFieldNames =
                    fieldRules.collectMany { rule -> rule.getValue().bibFields.collect { s -> [authfield: rule.getKey(), bibField: s] } }



            def bibFieldGroups =
                    doc.fields
                            .findAll { bibField -> auhtLinkableFieldNames.collect { it -> it.bibField }.contains(bibField.keySet()[0]) }
                            .groupBy { bibField -> auhtLinkableFieldNames.find { a -> a.bibField == bibField.keySet()[0] }.authfield }

            bibFieldGroups.each { group ->
                group.value.sort { field ->
                    def subfields = normaliseSubfields(field[field.keySet()[0]].subfields)
                    def foundSF = subfields.findAll { it ->
                        !fieldRules[group.key].subFieldsToIgnore.bib.contains(it.keySet()[0])
                    }
                    return -foundSF.collect { it -> it }.count { it }
                }
            }

            specGroups.each { specGroup ->
                def bibFieldGroup = bibFieldGroups.find {
                    it.key == specGroup.key
                }
                if (!bibFieldGroup?.value)
                    println "No biblFieldGroup values. Specgroup: ${specGroup?.key}, AuthFields: ${specGroups.collect { it.key }} Bibfields:${bibFieldGroups.collect { it }} BIBId: ${setSpecs.first()?.bibid} AUTHId: ${setSpecs.first()?.id}"
                else {
                    //println "Specgroup: ${specGroup?.key}, AuthFields: ${specGroups.count { it.value }} against  ${bibFieldGroup?.key}, Bibfields: ${bibFieldGroup?.value.count { it }} "
                    specGroup.value.each { spec ->
                       // println spec.normalizedSubfields


                        def matches = bibFieldGroup.value.collect { field ->
                            Map returnMap = [diff: null, reversediff: null, bibfield: field, spec: spec, errorMessage: ""]

                            def rule = fieldRules[spec.field]
                            if (!rule) {
                                returnMap.errorMessage = "No RULE ${field.keySet()[0]}"
                                return returnMap

                            } else {
                                def sf = normaliseSubfields(field[field.keySet()[0]].subfields).findAll {
                                    !rule.subFieldsToIgnore.bib.contains(it.keySet()[0])
                                }
                                returnMap.diff = sf.toSet() - spec.normalizedSubfields.toSet()
                                returnMap.reversediff = spec.normalizedSubfields.toSet() - sf.toSet()
                                return returnMap
                            }

                        }

                    }
                    /*if (diff.count {
                                  it
                              } == 0 && reversediff.count { it } == 0) {
                                  println "Match - No differences Auth:${spec.normalizedSubfields} Bib: ${sf}"
                              } else if (diff.count {
                                  it
                              } == 0 && reversediff.count { it } > 0) {
                                  println "Match with ${reversediff.count { it }} subfields overlap. Diff: ${reversediff} Auth:${spec.normalizedSubfields} Bib: ${sf}"
                              } else {
                                  println "No match with ${reversediff.count { it }} subfields overlap. Diff: ${reversediff} Auth:${spec.normalizedSubfields} Bib: ${sf}"
                              }*/

                }


            }

            /* //Iterate over the auth records. All should match
            setSpecs.findAll { it -> !ignoredAuthFields.contains(it.field) }.each { authField ->
                def fieldRule = fieldRules[authField.field]
                if (!fieldRule)
                    throw new Exception("Missing rule for authority field ${authField.field} bibid: ${authField.bibid}  auth: ${authField.id}")
                def bibFields = doc.fields.findAll { bibField ->
                    fieldRule.bibFields.contains(bibField.keySet()[0])
                }
                def authSubFields = normaliseSubfields(authField.subfields)
                        .findAll { it -> !fieldRule.subFieldsToIgnore.auth.contains(it.keySet()[0]) }
                        .collect { it -> it }

                bibFields.each { bibField ->
                    //def allSub = bibField[bibField.keySet()[0]].subfields
                    //if (allSub.any { it -> it.keySet()[0] == '0' }) {
                    def bibSubFields = normaliseSubfields(bibField[bibField.keySet()[0]].subfields)
                            .findAll { it -> !fieldRule.subFieldsToIgnore.bib.contains(it.keySet()[0]) }
                            .collect { it -> it }

                    def diff = bibSubFields.toSet() - authSubFields.toSet()
                    def diffKeys = diff.collect { it -> it.keySet().first() }
                    if (diff.count { it } > 0) {

                        misMatchedFields.add([bib: bibField.keySet()[0], authField: authField.field, diff: diff, auth: authSubFields, bib: bibSubFields])
                    }

                    if (diff.count { it } > 0 &&
                            !diffKeys.contains('a') &&
                            !(diffKeys.contains('b') && authField.field == '110') &&
                            !(diffKeys.contains('p') && authField.field == '130') &&
                            !(diffKeys.contains('z') && authField.field == '151') &&
                            !(diffKeys.contains('x') && authField.field == '150') &&
                            !(diffKeys.contains('y') && authField.field == '150') &&
                            !(diffKeys.contains('t') && authField.field == '100') &&
                            !(diffKeys.contains('0') && authField.field == '100')
                    )
                        println "Diff! Field: ${authField.field}/${bibField.keySet()[0]} ${diff} auth: ${authSubFields} bib: ${bibSubFields} bibid: https://libris.kb.se/bib/${authField.bibid} authid:https://libris.kb.se/auth/${authField.id}"

                    if (diff.count { it } == 0) {
                        authField.put('matched', 'matched')
                        matchedFields.add([diff: diff, bib: bibField.keySet()[0], auth: authField.field])
                        //bibField[bibField.keySet()[0]].subfields.add([0: "https://libris.kb.se/auth/${authField.id}"])
                    }
                }
                //}

            }
            if (matchedFields.count {
                it
            } > setSpecs.count { it -> !ignoredAuthFields.contains(it.field) }) {
                println "Matches: ${matchedFields.count { it }}/${setSpecs.count { it -> !ignoredAuthFields.contains(it.field) }}"
            }
            if (matchedFields.count {
                it
            } < setSpecs.count { it -> !ignoredAuthFields.contains(it.field) }) {
                println "Matches: ${matchedFields.count { it }}/${setSpecs.count { it -> !ignoredAuthFields.contains(it.field) }}"
                setSpecs.findAll { it -> !it.matched }.each {
                    println "Unmatched Spec: ${normaliseSubfields(it.subfields)}"
                }
                matchedFields.each { it ->
                    println "\t${it}"
                }
                println "misMatches:"
                misMatchedFields.each { it ->
                    println "\t${it}"
                }
            }
        */
            /* setSpecs.each { it ->
                 println it.value
                 if (it.type == "personalName") {
                     if (doc.fields.'100'.subfields.a.first().first() == it.value) {
                         println "match!"
                     }
                     if(doc.fields.'700'.subfields.a.first().first() == it.value){
                         println "match!"
                     }
                 }
             }*/
        }
    }

    static def normaliseSubfields(def subfields) {
        subfields.collect {
            it.collect { k, v -> [(k): (v as String).replaceAll(/(^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+\u0024)|(\s)/, "").toLowerCase()] }[0]
        }

    }

    static String getSubfieldValue(Map p, String s) {

        for (subfield in p.subfields) {
            String key = subfield.keySet()[0];
            if (key == s) {
                return subfield[key]
            }
        }
        return ""
    }

}
