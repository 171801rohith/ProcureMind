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