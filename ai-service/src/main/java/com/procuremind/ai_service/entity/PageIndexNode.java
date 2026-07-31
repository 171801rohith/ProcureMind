package com.procuremind.ai_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "page_index_nodes")
@Setter
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PageIndexNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID documentId;
    private UUID parentNodeId;
    private Integer level;
    private Integer nodeOrder;

    @Enumerated(EnumType.STRING)
    private NodeType nodeType;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String rawContext;
}
