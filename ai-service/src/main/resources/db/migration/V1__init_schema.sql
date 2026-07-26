CREATE TABLE page_index_nodes
(
    id             UUID NOT NULL,
    document_id    UUID,
    parent_node_id UUID,
    level          INTEGER,
    node_order     INTEGER,
    node_type      VARCHAR(255),
    title          VARCHAR(255),
    summary        VARCHAR(255),
    raw_context    TEXT,
    CONSTRAINT pk_page_index_nodes PRIMARY KEY (id)
);