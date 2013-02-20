package se.kb.libris.whelks.plugin;

import java.net.URI;

import se.kb.libris.whelks.basic.BasicPlugin;
import se.kb.libris.whelks.Document;
import se.kb.libris.whelks.Whelk;

public abstract class AbstractTrigger extends BasicPlugin implements Trigger,WhelkAware {

    private Whelk whelk = null;

    @Override
    public void setWhelk(Whelk w) { this.whelk = w;}
    public Whelk getWhelk() { return this.whelk; }
    @Override
    public void beforeStore(Document d) {}
    @Override
    public void afterStore(Document d) {}
    @Override
    public void beforeGet(Document d) {}
    @Override
    public void afterGet(Document d) {}
    @Override
    public void beforeDelete(URI uri) {}
    @Override
    public void afterDelete(URI uri) {}
}