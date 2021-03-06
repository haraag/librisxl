CREATE INDEX idx_lddb_alive ON lddb (id) WHERE deleted IS NOT true;
CREATE INDEX idx_lddb_modified ON lddb (modified);

CREATE INDEX idx_lddb_graph ON lddb USING GIN ((data->'@graph') jsonb_path_ops);
CREATE INDEX idx_lddb_holding_for on lddb ((data#>>'{@graph,1,holdingFor,@id}'));
CREATE INDEX idx_lddb_systemnumber on lddb using gin ((data#>'{@graph,0,systemNumber}'));
CREATE INDEX idx_lddb_thing_identifiers on lddb using gin ((data#>'{@graph,1,identifier}'));

CREATE INDEX idx_lddb__identifiers_id ON lddb__identifiers (id);
CREATE INDEX idx_lddb__identifiers_identifier ON lddb__identifiers (identifier);

CREATE INDEX idx_lddb__versions_id ON lddb__versions (id);
CREATE INDEX idx_lddb__versions_modified ON lddb__versions (modified);
CREATE INDEX idx_lddb__versions_checksum ON lddb__versions (checksum);
