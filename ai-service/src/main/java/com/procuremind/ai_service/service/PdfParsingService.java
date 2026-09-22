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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class PdfParsingService {
    private final MinioClient minioClient;
    private final PageIndexNodeRepository nodeRepository;

    private final Tika tika = createTika();

    private static Tika createTika() {
        Tika t = new Tika();
        t.setMaxStringLength(-1);
        return t;
    }

    private static final String BUCKET_NAME = "procuremind-contracts";

    private static final Pattern ARTICLE_PATTERN = Pattern.compile(
            "^ARTICLE\\s+([IVXLCDM]+|\\d+)\\.?\\s*[-–—:]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)+)\\.?\\s*[-–—:]?\\s*(.*)$"
    );

    /**
     * Fallback for flat, single-level numbering such as {@code "1. Definitions"}.
     *
     * <p>{@link #SECTION_PATTERN} requires at least one dotted sub-level (e.g. {@code "1.1"})
     * and never matches a bare {@code "1."}, so a contract that numbers its top-level clauses
     * without sub-sections fell through to body text on every heading. This is tried only
     * after {@link #SECTION_PATTERN} fails, so nested numbering keeps matching the richer
     * pattern first; the {@link #startsLikeTitle} guard still applies, so an ordinary sentence
     * that happens to start with a number (e.g. "3 dollars per unit...") is not mistaken for
     * a heading.
     */
    private static final Pattern FLAT_SECTION_PATTERN = Pattern.compile(
            "^(\\d+)\\.?\\s*[-–—:]?\\s*(.*)$"
    );

    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^(page\\s+\\d+(\\s+of\\s+\\d+)?|\\d+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final int MAX_HEADER_LINE_LENGTH = 150;

    @Transactional
    public void parseAndIndexPdf(UUID documentId, String minioObjName) {
        if (nodeRepository.existsByDocumentId(documentId)) {
            log.info("Document [{}] already has parsed index nodes. Skipping duplicate parsing.", documentId);
            return;
        }

        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(minioObjName)
                        .build()
        )) {
            String fullText = tika.parseToString(stream);
            log.info("Successfully extracted text from PDF. Length: {} chars", fullText.length());

            int order = 1;

            PageIndexNode root = PageIndexNode.builder()
                    .documentId(documentId)
                    .parentNodeId(null)
                    .level(1)
                    .nodeOrder(order++)
                    .nodeType(NodeType.ROOT)
                    .title("Document Root")
                    .rawContext("FULL DOCUMENT")
                    .build();
            root = nodeRepository.save(root);

            Map<Integer, PageIndexNode> lastNodeAtLevel = new HashMap<>();

            PageIndexNode curArticle = null;
            PageIndexNode currentNode = root;
            StringBuilder bodyBuffer = new StringBuilder();

            String[] lines = fullText.split("\\R");

            for (String rawLine : lines) {
                String text = rawLine.trim();
                if (text.isEmpty()) continue;
                if (NOISE_PATTERN.matcher(text).matches()) continue;

                boolean headCandidate = text.length() <= MAX_HEADER_LINE_LENGTH;

                if (headCandidate) {
                    Matcher articleMatcher = ARTICLE_PATTERN.matcher(text);
                    if (articleMatcher.matches()) {
                        currentNode = finalizeNode(currentNode, bodyBuffer);

                        String title = articleMatcher.group(2).isBlank()
                                ? "ARTICLE" + articleMatcher.group(1)
                                : articleMatcher.group(2).trim();

                        curArticle = PageIndexNode.builder()
                                .documentId(documentId)
                                .parentNodeId(root.getId())
                                .level(2)
                                .nodeOrder(order++)
                                .nodeType(NodeType.ARTICLE)
                                .title(title)
                                .rawContext(text)
                                .build();
                        curArticle = nodeRepository.save(curArticle);

                        lastNodeAtLevel.put(2, curArticle);
                        clearDeeperLevels(lastNodeAtLevel, 2);
                        currentNode = curArticle;
                        continue;
                    }
                    Matcher sectionMatcher = SECTION_PATTERN.matcher(text);
                    boolean dotted = sectionMatcher.matches() && startsLikeTitle(sectionMatcher.group(2));
                    if (!dotted) {
                        Matcher flatMatcher = FLAT_SECTION_PATTERN.matcher(text);
                        if (flatMatcher.matches() && startsLikeTitle(flatMatcher.group(2))) {
                            sectionMatcher = flatMatcher;
                        } else {
                            sectionMatcher = null;
                        }
                    }
                    if (sectionMatcher != null) {
                        currentNode = finalizeNode(currentNode, bodyBuffer);

                        String numbering = sectionMatcher.group(1);
                        int depth = numbering.split("\\.").length;
                        int level = depth + 1;

                        PageIndexNode parent = lastNodeAtLevel.getOrDefault(
                                level - 1,
                                curArticle != null ? curArticle : root
                        );

                        String title = sectionMatcher.group(2).isBlank()
                                ? numbering
                                : sectionMatcher.group(2).trim();

                        PageIndexNode section = PageIndexNode.builder()
                                .documentId(documentId)
                                .parentNodeId(parent.getId())
                                .level(level)
                                .nodeOrder(order++)
                                .nodeType(NodeType.SECTION)
                                .title(title)
                                .rawContext(text)
                                .build();
                        section = nodeRepository.save(section);

                        lastNodeAtLevel.put(level, section);
                        clearDeeperLevels(lastNodeAtLevel, level);
                        currentNode = section;
                        continue;
                    }
                }
                bodyBuffer.append(text).append("\n");
            }
            finalizeNode(currentNode, bodyBuffer);
            log.info("Finished initial indexing {}", documentId);
        } catch (Exception e) {
            log.error("Failed to parse PDF for contract {}", documentId, e);
            throw new RuntimeException("PDF Parsing failed", e);
        }
    }

    private PageIndexNode finalizeNode(PageIndexNode node, StringBuilder bodyBuffer) {
        if (!bodyBuffer.isEmpty()) {
            String body = bodyBuffer.toString().trim();
            String existing = node.getRawContext();

            String combined = (existing == null || existing.isBlank())
                    ? body
                    : existing + "\n" + body;
            node.setRawContext(combined);
            node = nodeRepository.save(node);
            bodyBuffer.setLength(0);
        }
        return node;
    }

    private void clearDeeperLevels(Map<Integer, PageIndexNode> lastNodeAtLevel, int level) {
        lastNodeAtLevel.keySet().removeIf(l -> l > level);
    }

    private boolean startsLikeTitle(String remainder) {
        if (remainder == null || remainder.isBlank()) return true;
        return Character.isUpperCase(remainder.trim().charAt(0));
    }
}
