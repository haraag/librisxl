package whelk.tools

/**
 * Created by Theodor on 2016-11-21.
 */
class SetSpecMatcher {

    //static List auhtLinkableFieldNames = ['100', '600', '700', '800', '110', '610', '710', '810', '130', '630', '730', '830', '650', '651', '655']
    static List ignoredAuthFields = ['180', '181', '182', '185', '162', '148']
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
            '150': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['650']],
            '151': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['651']],
            '155': [subFieldsToIgnore: [bib: ['0', '4'], auth: ['6']],
                    bibFields        : ['655']]
    ]


    static Map matchAuthToBib(Map doc, List allSetSpecs) {
        List setSpecs = allSetSpecs.findAll { it -> !ignoredAuthFields.contains(it.field) }
        int ignoredSetSpecs = allSetSpecs.findAll { it -> ignoredAuthFields.contains(it.field) }.count {
            it
        }
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

            Map specGroupsResult = [SolidMatches: 0, MisMatchesOnA: 0, MisMatchesOnB: 0, bibInAukt: 0, auktInBib: 0, doubleDiff: 0, possibleMatches: 0, ignoredSetSpecs: ignoredSetSpecs]

            def possibleBibfieldsFromSetSpec = auhtLinkableFieldNames.findAll { f ->
                setSpecs.collect {
                    it.field
                }.contains(f.authfield)
            }.collect { it.bibField }
            def linkableBibfields = bibFieldGroups.collectMany { c ->
                c.value.collect {
                    it.keySet()[0]
                }
            }
            def bibFieldsWithoutAuthField = linkableBibfields.findAll { it -> !possibleBibfieldsFromSetSpec.contains(it) }

            specGroups.each { specGroup ->
                def statsFile = new File("/Users/Theodor/libris/uncertainmatches.tsv")
                def bibFieldGroup = bibFieldGroups.find {
                    it.key == specGroup.key
                }
                if (!bibFieldGroup?.value) {
                   // def file = new File("/Users/Theodor/libris/missingbibfields.tsv")
                   // file << "${specGroup?.key}\t ${bibFieldsWithoutAuthField} \t${setSpecs.first()?.bibid} \t${setSpecs.first()?.id} \t  http://libris.kb.se/bib/${setSpecs.first()?.bibid}?vw=full&tab3=marc \t http://libris.kb.se/auth/${setSpecs.first()?.id}\n"
                } else {
                    //println "Specgroup: ${specGroup?.key}, AuthFields: ${specGroups.count { it.value }} against  ${bibFieldGroup?.key}, Bibfields: ${bibFieldGroup?.value.count { it }} "
                    specGroup.value.each { spec ->
                        // println spec.normalizedSubfields

                        def diffs = getSetDiffs(spec, bibFieldGroup.value, fieldRules, doc)


                        def completeMatches = diffs.findAll { match -> match.isMatch }

                        def misMatchesOnA = diffs.findAll { match -> match.hasMisMatchOnA }

                        def uncertainMatches = diffs.findAll { match ->
                            match.hasOnlyDiff || match.hasOnlyReverseDiff || match.hasDoubleDiff
                        }

                        specGroupsResult.possibleMatches += 1

                        specGroupsResult.SolidMatches += completeMatches.count {
                            it
                        }
                        specGroupsResult.MisMatchesOnA += misMatchesOnA.count {
                            it
                        }
                        specGroupsResult.bibInAukt += uncertainMatches.count { match ->
                            match.hasOnlyDiff
                        }
                        specGroupsResult.auktInBib += uncertainMatches.count { match ->
                            match.hasOnlyReverseDiff
                        }
                        specGroupsResult.doubleDiff += uncertainMatches.count { match ->
                            match.hasDoubleDiff
                        }

                        printDiffResultsToFile(statsFile, uncertainMatches, doc, setSpecs)


                    }
                }
            }
            return specGroupsResult
        }
    }

    static
    def printDiffResultsToFile(File file, List<Map> matches, doc, setSpecs) {
        matches.each { match ->
            file << "${match.type}" +
                    "\t${match.diff.count { it }}" +
                    "\t${match.spec.field}" +
                    "\t${match.bibField}" +
                    "\t${match.subfieldsInOverlap}" +
                    "\t${match.subfieldsInDiff}" +
                    "\t${match.subfieldsInReversediff}" +
                    "\t${match.reverseDiff.count { it }}" +
                    "\t${match.overlap.count { it }}" +
                    "\t${doc.leader?.substring(5, 6) ?: ''}" +
                    "\t${doc.leader?.substring(6, 7) ?: ''}" +
                    "\t${doc.leader?.substring(7, 8) ?: ''}" +
                    "\t${doc.leader?.substring(17, 18) ?: ''}" +
                    "\t${doc.fields?."008"?.find { it -> it }?.take(2) ?: ''}" +
                    "\t _" +
                    "\t${doc.fields?."040"?.find { it -> it }?.subfields?."a"?.find { it -> it } ?: ''}" +
                    "\t${doc.fields?."040"?.find { it -> it }?.subfields?."d"?.find { it -> it } ?: ''}" +
                    "\t${match.spec.data.leader?.substring(5, 6) ?: ''}" +
                    "\t${match.spec.data.leader?.substring(6, 7) ?: ''}" +
                    "\t${match.spec.data.fields?."008"?.find { it -> it }?.take(2) ?: ''}" +
                    "\t${match.spec.data.fields?."008"?.find { it -> it }?.substring(9, 10) ?: ''}" +
                    "\t${match.spec.data.fields?."008"?.find { it -> it }?.substring(33, 34) ?: ''}" +
                    "\t${match.spec.data.fields?."008"?.find { it -> it }?.substring(39, 40) ?: ''}" +
                    "\t${match.numBibFields}" +
                    "\t${match.numAuthFields}" +
                    "\t${match.partialD}" +
                    "\t${match.bibHas035a}" +
                    "\t${match.bibSet} " +
                    "\t${match.authSet} " +
                    "\t${setSpecs.first()?.bibid} " +
                    "\t${setSpecs.first()?.id} " +
                    "\n"

        }

    }

    private
    static List<Map> getSetDiffs(setSpec, bibFieldGroup, Map fieldRules, doc) {
        bibFieldGroup.collect { field ->

            def rule = fieldRules[setSpec.field]
            if (rule) {
                def bibSubFields = normaliseSubfields(field[field.keySet()[0]].subfields).findAll {
                    !rule.subFieldsToIgnore.bib.contains(it.keySet()[0])
                }
                Set bibSet = bibSubFields.toSet()
                Set authSet = setSpec.normalizedSubfields.toSet()
                Set diff = authSet - bibSet
                Set reverseDiff = bibSet - authSet
                Set overlap = bibSet.intersect(authSet)
                boolean isMatch = (diff.count { it } == 0 &&
                        reverseDiff.count { it } == 0)
                boolean hasMisMatchOnA = reverseDiff.find { it -> it.a } != null && diff.find { it -> it.a } != null
                boolean hasOnlyDiff = diff.count {
                    it
                } > 0 && reverseDiff.count { it } == 0
                boolean hasOnlyReverseDiff = diff.count {
                    it
                } == 0 && reverseDiff.count { it } > 0
                boolean hasDoubleDiff = diff.count {
                    it
                } > 0 && reverseDiff.count { it } > 0
                boolean bibHas035a = getHas035a(doc)
                boolean partialMatchOnSubfieldD = getPartialMatchOnSubfieldD(diff, reverseDiff, setSpec.field)
                Map returnMap = [
                        diff                  : diff,
                        reverseDiff           : reverseDiff,
                        bibField          : field.keySet().first(),
                        spec                  : setSpec,
                        errorMessage          : "",
                        overlap               : overlap,
                        numBibFields          : bibSet.count { it },
                        numAuthFields         : authSet.count { it },
                        subfieldsInOverlap    : overlap.collect { it -> it.keySet().first() }.toSorted().join(),
                        subfieldsInDiff       : diff.collect { it -> it.keySet().first() }.toSorted().join(),
                        subfieldsInReversediff: reverseDiff.collect { it -> it.keySet().first() }.toSorted().join(),
                        isMatch               : isMatch,
                        hasMisMatchOnA        : hasMisMatchOnA,
                        hasOnlyDiff           : hasOnlyDiff,
                        hasOnlyReverseDiff    : hasOnlyReverseDiff,
                        hasDoubleDiff         : hasDoubleDiff,
                        bibHas035a            : bibHas035a,
                        partialD              : partialMatchOnSubfieldD,
                        bibSet                : bibSet,
                        authSet               : authSet


                ]

                //TODO: print stuff to file instead

                /*if((returnMap.bibfield as String).startsWith("6")){
                    def file = new File("/Users/Theodor/libris/subjectOccurrences.tsv")
                    file << "\t${setSpec.field}\t${returnMap.bibfield}\t${field[field.keySet().first()]?.'ind2'}\t${bibSubFields.find{it->it.'2' != null}?.'2'}\n"
                }*/

                returnMap.type = getMatchType(returnMap)

                return returnMap
            }

        }
    }

    static boolean getPartialMatchOnSubfieldD(Set diff, Set reverseDiff, specField) {
        boolean partialD = false
        try {
            def bibD = reverseDiff.collect { it -> it.d }
            def auktD = diff.collect { it -> it.d }
            boolean hasValues = (specField == '100' && bibD.any() && auktD.any() && bibD.first() != '' && auktD.first() != '')
            partialD = hasValues ? (bibD.first()?.substring(0, 4) == auktD.first()?.substring(0, 4)) : false
        }
        catch (any) {
            println any.message
        }
        return partialD
    }

    static boolean getHas035a(p) {

        try {
            def a = p.fields?.'035'?.subfields?.a
            return a && a.any() && a.first() && a.first().any()
        }
        catch (any) {
            println any.message
            return false
        }
    }

    static String getMatchType(Map map) {
        switch (map) {
            case map.isMatch:
                return "match"
                break
            case map.hasOnlyDiff:
                return "hasOnlyDiff"
                break
            case map.hasOnlyReverseDiff:
                return "hasOnlyReverseDiff"
                break
            case map.hasDoubleDiff:
                return "hasDoubleDiff"
                break
            case map.hasMisMatchOnA:
                return "hasMisMatchOnA"
                break
            default:
                "other"
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
