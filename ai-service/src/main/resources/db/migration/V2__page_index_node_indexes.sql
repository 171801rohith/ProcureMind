-- Every retrieval path (getContractSummary, the indexing driver query, the TOC endpoint)
-- filters page_index_nodes by document_id, and the agents issue several of those per
-- analysis. Without this the table is sequentially scanned on each tool call.
CREATE INDEX IF NOT EXISTS idx_page_index_nodes_document_id
    ON page_index_nodes (document_id);

-- The indexing loop's driver query is "document_id = ? AND summary IS NULL"; a partial
-- index keeps it cheap even once most nodes are summarised.
CREATE INDEX IF NOT EXISTS idx_page_index_nodes_unindexed
    ON page_index_nodes (document_id)
    WHERE summary IS NULL;
