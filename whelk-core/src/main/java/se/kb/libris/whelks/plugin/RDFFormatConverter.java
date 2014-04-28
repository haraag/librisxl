package se.kb.libris.whelks.plugin;

import se.kb.libris.whelks.RDFDescription;
import se.kb.libris.whelks.Document;
import se.kb.libris.whelks.Whelk;
import java.util.Map;

public interface RDFFormatConverter extends Plugin {
    public Map<String, RDFDescription> convert(Document doc);
    public String getRequiredContentType();
}
