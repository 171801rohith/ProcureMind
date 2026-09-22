-- page_index_nodes.parent_node_id backs the tree-walk queries (children-of-node lookups
-- used when building/rendering the page index); no index exists on it today.
CREATE INDEX IF NOT EXISTS idx_page_index_nodes_parent_node_id
    ON page_index_nodes (parent_node_id);

-- analysis_risks.analysis_id is a foreign key to contract_analysis; Postgres does not
-- auto-index FK columns, and every risk-listing-by-analysis query filters on it.
CREATE INDEX IF NOT EXISTS idx_analysis_risks_analysis_id
    ON analysis_risks (analysis_id);
