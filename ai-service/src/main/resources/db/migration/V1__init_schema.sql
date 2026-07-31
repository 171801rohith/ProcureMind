CREATE TABLE page_index_nodes
(
    id             UUID NOT NULL,
    document_id    UUID,
    parent_node_id UUID,
    level          INTEGER,
    node_order     INTEGER,
    node_type      VARCHAR(255),
    title          TEXT,
    summary        TEXT,
    raw_context    TEXT,
    CONSTRAINT pk_page_index_nodes PRIMARY KEY (id)
);

CREATE TABLE contracts
(
    id                UUID NOT NULL,
    filename          VARCHAR(255),
    minio_object_name VARCHAR(255),
    vendor_name       VARCHAR(255),
    status            VARCHAR(255),
    uploaded_at       TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_contracts PRIMARY KEY (id)
);

CREATE TABLE analysis_risks
(
    id          UUID NOT NULL,
    analysis_id UUID,
    severity    VARCHAR(255),
    description VARCHAR(255),
    CONSTRAINT pk_analysis_risks PRIMARY KEY (id)
);

CREATE TABLE contract_analysis
(
    id             UUID NOT NULL,
    contract_id    UUID NOT NULL,
    vendor_name    VARCHAR(255),
    risk_score     DOUBLE PRECISION,
    recommendation VARCHAR(255),
    status         VARCHAR(255),
    created_at     TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_contract_analysis PRIMARY KEY (id)
);

ALTER TABLE contract_analysis
    ADD CONSTRAINT uc_contract_analysis_contractid UNIQUE (contract_id);

ALTER TABLE analysis_risks
    ADD CONSTRAINT FK_ANALYSIS_RISKS_ON_ANALYSIS FOREIGN KEY (analysis_id) REFERENCES contract_analysis (id);