package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.entity.NodeType;
import com.procuremind.ai_service.entity.PageIndexNode;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class PdfParsingService {
    private final MinioClient minioClient;
    private final PageIndexNodeRepository nodeRepository;
    private final Tika tika = new Tika();

    private static final String BUCKET_NAME = "procuremind-contracts";

    private static final Pattern ARTICLE_PATTERN =
            Pattern.compile("^Article\\s+[IVXLC]+.*", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECTION_PATTERN =
            Pattern.compile("^\\d+(\\.\\d+)+.*");

    @Transactional
    public void parseAndIndexPdf(UUID documentId, String minioObjName) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(minioObjName)
                        .build()
        )) {

            String fullText = tika.parseToString(stream);
            int order = 1;
            log.info("Successfully extracted text from PDF. Length: {} chars", fullText.length());

            PageIndexNode root = PageIndexNode.builder()
                    .documentId(documentId)
                    .parentNodeId(null)
                    .level(1)
                    .nodeOrder(order++)
                    .nodeType(NodeType.ROOT)
                    .rawContext("FULL DOCUMENT")
                    .build();

            root = nodeRepository.save(root);

            String[] lines = fullText.split("\\R");
            StringBuilder sectionContent = new StringBuilder();
            PageIndexNode curArticle = null;


            for (String line : lines) {
                String text = line.trim();
                if (text.length() < 15) continue;

                if (ARTICLE_PATTERN.matcher(text).matches()) {

                    flushSection(documentId, root, curArticle, sectionContent, order++);

                    sectionContent.setLength(0);

                    curArticle = nodeRepository.save(
                            PageIndexNode.builder()
                                    .documentId(documentId)
                                    .parentNodeId(root.getId())
                                    .level(2)
                                    .nodeOrder(order++)
                                    .nodeType(NodeType.ARTICLE)
                                    .rawContext(text)
                                    .build()
                    );
                    continue;
                }

                if (SECTION_PATTERN.matcher(text).matches()) {
                    flushSection(documentId, root, curArticle, sectionContent, order++);

                    sectionContent.setLength(0);
                }
                sectionContent.append(text).append("\n");
            }

            flushSection(documentId,
                    root,
                    curArticle,
                    sectionContent,
                    order);

            log.info("Finished initial indexing {}", documentId);

        } catch (Exception e) {
            log.error("Failed to parse PDF for contract {}", documentId, e);
            throw new RuntimeException("PDF Parsing failed", e);
        }
    }

    private void flushSection(UUID documentId, PageIndexNode root, PageIndexNode article, StringBuilder builder, int order) {
        String text = builder.toString().trim();
        if (text.isEmpty()) return;

        nodeRepository.save(
                PageIndexNode.builder()
                        .documentId(documentId)
                        .parentNodeId(
                                article == null ? root.getId() : article.getId()
                        )
                        .level(article == null ? 2 : 3)
                        .nodeOrder(order)
                        .nodeType(NodeType.SECTION)
                        .rawContext(text)
                        .build()
        );
    }
}
