package se.kb.libris.whelks.component;

import java.net.URI;
import java.io.OutputStream;
import se.kb.libris.whelks.Document;

public interface Storage extends Component {
    public OutputStream getOutputStreamFor(URI identifier, String contentType);
    public void store(Document d);
    public Document get(URI uri);
    public void delete(URI uri);
}
