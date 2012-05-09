package se.kb.libris.whelks

import org.junit.Test

class WhelksTest {

    @Test
    void test_create_random_URI() {
        println "testing uri"
        Whelk w = new WhelkImpl("foo")
        def uri = w._create_random_URI()
        assert uri instanceof java.net.URI
    }

    @Test
    void testIsBinaryData() {
        def w = new WhelkImpl("foo")
        assert ! w.isBinaryData("foo".getBytes())
    } 
}
