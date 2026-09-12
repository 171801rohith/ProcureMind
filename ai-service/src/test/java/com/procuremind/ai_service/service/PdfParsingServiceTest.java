package com.procuremind.ai_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.entity.NodeType;
import com.procuremind.ai_service.entity.PageIndexNode;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The hierarchy this parser builds is the whole retrieval index - there are no embeddings
 * behind it - so the article/section nesting, the parent links and the noise filtering are
 * load-bearing behaviour rather than formatting details.
 */
@ExtendWith(MockitoExtension.class)
class PdfParsingServiceTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String OBJECT_NAME = "44444444_msa.pdf";

    private static final String CONTRACT_TEXT = """
            ARTICLE I - DEFINITIONS
            Capitalized terms have the meanings given in this Article.
            1.1 Payment Terms
            Net 30 from the invoice date.
            1.1.1 Late Fees
            Two percent per month on overdue amounts.
            Page 3 of 12
            ARTICLE II: TERM
            This agreement runs for three years.
            """;

    @Mock
    MinioClient minioClient;

    @Mock
    PageIndexNodeRepository nodeRepository;

    private final List<PageIndexNode> saved = new ArrayList<>();

    @Test
    void buildsTheArticleAndSectionHierarchyFromTheExtractedText() throws Exception {
        givenTheDocumentIsNotYetParsed();

        new PdfParsingService(minioClient, nodeRepository).parseAndIndexPdf(DOCUMENT_ID, OBJECT_NAME);

        assertThat(saved).hasSize(5);
        PageIndexNode root = saved.get(0);
        PageIndexNode articleOne = saved.get(1);
        PageIndexNode paymentTerms = saved.get(2);
        PageIndexNode lateFees = saved.get(3);
        PageIndexNode articleTwo = saved.get(4);

        assertThat(root.getNodeType()).isEqualTo(NodeType.ROOT);
        assertThat(root.getLevel()).isEqualTo(1);
        assertThat(root.getParentNodeId()).isNull();

        assertThat(articleOne.getNodeType()).isEqualTo(NodeType.ARTICLE);
        assertThat(articleOne.getLevel()).isEqualTo(2);
        assertThat(articleOne.getTitle()).isEqualTo("DEFINITIONS");
        assertThat(articleOne.getParentNodeId()).isEqualTo(root.getId());

        // A section nests under the article, and a sub-section under that section, purely
        // from its dotted numbering depth.
        assertThat(paymentTerms.getLevel()).isEqualTo(3);
        assertThat(paymentTerms.getTitle()).isEqualTo("Payment Terms");
        assertThat(paymentTerms.getParentNodeId()).isEqualTo(articleOne.getId());

        assertThat(lateFees.getLevel()).isEqualTo(4);
        assertThat(lateFees.getTitle()).isEqualTo("Late Fees");
        assertThat(lateFees.getParentNodeId()).isEqualTo(paymentTerms.getId());

        // A new article resets the depth rather than nesting under the previous section.
        assertThat(articleTwo.getLevel()).isEqualTo(2);
        assertThat(articleTwo.getTitle()).isEqualTo("TERM");
        assertThat(articleTwo.getParentNodeId()).isEqualTo(root.getId());
    }

    @Test
    void bodyTextLandsOnItsOwnSectionAndPageFurnitureIsDropped() throws Exception {
        givenTheDocumentIsNotYetParsed();

        new PdfParsingService(minioClient, nodeRepository).parseAndIndexPdf(DOCUMENT_ID, OBJECT_NAME);

        assertThat(saved.get(1).getRawContext()).contains("Capitalized terms have the meanings given");
        assertThat(saved.get(2).getRawContext()).contains("Net 30 from the invoice date.");
        assertThat(saved.get(3).getRawContext()).contains("Two percent per month");
        assertThat(saved).noneSatisfy(node -> assertThat(node.getRawContext()).contains("Page 3 of 12"));
    }

    @Test
    void aRedeliveredUploadEventDoesNotReparseTheDocument() throws Exception {
        given(nodeRepository.existsByDocumentId(DOCUMENT_ID)).willReturn(true);

        new PdfParsingService(minioClient, nodeRepository).parseAndIndexPdf(DOCUMENT_ID, OBJECT_NAME);

        // Idempotency guard: neither the object store nor the node table is touched again.
        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
        verify(nodeRepository).existsByDocumentId(DOCUMENT_ID);
        verifyNoMoreInteractions(nodeRepository);
    }

    private void givenTheDocumentIsNotYetParsed() throws Exception {
        given(nodeRepository.existsByDocumentId(DOCUMENT_ID)).willReturn(false);
        given(minioClient.getObject(any(GetObjectArgs.class))).willReturn(textResponse());
        given(nodeRepository.save(any(PageIndexNode.class))).willAnswer(invocation -> {
            PageIndexNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId(UUID.randomUUID());
            }
            if (saved.stream().noneMatch(existing -> existing == node)) {
                saved.add(node);
            }
            return node;
        });
    }

    private static GetObjectResponse textResponse() {
        return new GetObjectResponse(
                Headers.of("Content-Type", "text/plain"),
                "procuremind-contracts",
                null,
                OBJECT_NAME,
                new ByteArrayInputStream(CONTRACT_TEXT.getBytes(StandardCharsets.UTF_8)));
    }
}
