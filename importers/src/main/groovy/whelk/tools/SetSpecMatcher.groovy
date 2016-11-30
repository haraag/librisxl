package whelk.tools

/**
 * Created by Theodor on 2016-11-21.
 */
class SetSpecMatcher {

    //static List auhtLinkableFieldNames = ['100', '600', '700', '800', '110', '610', '710', '810', '130', '630', '730', '830', '650', '651', '655']
    static List ignoredAuthFields = ['180', '181', '182', '185', '162','148']
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


    static Map matchAuthToBib(Map doc, List allSetSpecs) {
        List matchedFields = []
        List misMatchedFields = []
        List setSpecs = allSetSpecs.findAll { it -> !ignoredAuthFields.contains(it.field) }
        //Prepare setspec groups
        if (setSpecs.any()) {
            setSpecs.each { spec ->
                if (!fieldRules[spec.field])
                    throw new Exception("No rules for field ${spec.field}")
                if (!fieldRules[spec.field].subFieldsToIgnore)
                    throw new Exception("No subFieldsToIgnore for field ${spec.field}")

                spec.put("normalizedSubfields",
                        normaliseSubfields(spec.subfields)
                                .findAll { it -> !fieldRules[spec.field].subFieldsToIgnore.auth.contains(it.keySet()[0]) }
                                .collect { it -> it })
            }
            def specGroups = setSpecs.groupBy { it.field }
                    .toSorted {
                -(it.hasProperty('normalizedSubfields') ? it.normalizedSubfields.count {
                    it
                } : 0)
            }

            //Prepare bib field groups
            List<String> auhtLinkableFieldNames =
                    fieldRules.collectMany { rule ->
                        rule.getValue().bibFields.collect { s ->
                            [authfield: rule.getKey(), bibField: s]
                        }
                    }

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

            Map specGroupsResult = [SolidMatches:0, MisMatchesOnA:0,bibInAukt:0,auktInBib:0,doubleDiff:0, possibleMatches:0]

            specGroups.each { specGroup ->
                def bibFieldGroup = bibFieldGroups.find {
                    it.key == specGroup.key
                }
                if (!bibFieldGroup?.value) {
                    def file = new File("/Users/Theodor/libris/missingbibfields.txt")
                    file <<"Specgroup: ${specGroup?.key}, AuthFields: ${specGroups.collect { it.key }} Bibfields:${bibFieldGroups.collect { it }} BIBId: ${setSpecs.first()?.bibid} AUTHId: ${setSpecs.first()?.id}\n"
                }
                else {
                    //println "Specgroup: ${specGroup?.key}, AuthFields: ${specGroups.count { it.value }} against  ${bibFieldGroup?.key}, Bibfields: ${bibFieldGroup?.value.count { it }} "
                    specGroup.value.each { spec ->
                        // println spec.normalizedSubfields


                        def diffs = getSetDiffs(spec, bibFieldGroup.value, fieldRules)


                        def completeMatches = diffs.findAll { match ->
                            match.diff.count { it } == 0 &&
                                    match.reversediff.count { it } == 0

                        }

                        def misMatchesOnA = diffs.findAll { match ->
                            match.reversediff.find { it -> it.a } != null
                        }


                        def uncertainMatches = diffs.findAll { match ->

                            match.reversediff.find { it -> it.a } == null &&
                                    (match.diff.count { it } > 0 ||
                                            match.reversediff.count { it } > 0)
                        }


                        /*if(bibFieldGroup.value.count { it } == completeMatches.count { it } + misMatchesOnA.count { it } )
                            println "All match"
                        if(uncertainMatches.count{it} >0)
                        {
                            println "\t${spec.field} \t${bibFieldGroup.value.count { it }} \t ${diffs.count { it }}\t ${completeMatches.count { it }} \t${misMatchesOnA.count { it }}"
                        }*/
                        specGroupsResult.possibleMatches +=1
                        specGroupsResult.SolidMatches += completeMatches.count{it}
                        specGroupsResult.MisMatchesOnA +=misMatchesOnA.count{it}
                        specGroupsResult.bibInAukt += uncertainMatches.count { match ->
                            match.diff.count { it } > 0 && match.reversediff.count { it } == 0
                        }
                        specGroupsResult.auktInBib += uncertainMatches.count { match ->
                            match.reversediff.count { it } > 0 &&
                                    match.diff.count { it } == 0
                        }
                        specGroupsResult.doubleDiff += uncertainMatches.count { match ->
                            match.reversediff.count { it } > 0 &&
                                    match.diff.count { it } > 0
                        }
                    }
                }
            }
            //println "\t${specGroupsResult.SolidMatches} \t${specGroupsResult.MisMatchesOnA} \t${specGroupsResult.bibInAukt} \t${specGroupsResult.auktInBib}"
            return specGroupsResult
        }
    }

    private
    static List<Map> getSetDiffs(setSpec, bibFieldGroup, Map fieldRules) {
        bibFieldGroup.collect { field ->
            Map returnMap = [diff: null, reversediff: null, bibfield: field, spec: setSpec, errorMessage: ""]

            def rule = fieldRules[setSpec.field]
            if (!rule) {
                returnMap.errorMessage = "No RULE ${field.keySet()[0]}"
                return returnMap

            } else {
                def sf = normaliseSubfields(field[field.keySet()[0]].subfields).findAll {
                    !rule.subFieldsToIgnore.bib.contains(it.keySet()[0])
                }
                returnMap.diff = sf.toSet() - setSpec.normalizedSubfields.toSet()
                returnMap.reversediff = setSpec.normalizedSubfields.toSet() - sf.toSet()
                //TODO: print stuff to file instead
                return returnMap
            }

        }
    }

    static def normaliseSubfields(subfields) {
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
