package com.procuremind.ai_service.controller;

import com.procuremind.ai_service.dto.AnalysisResponseDto;
import com.procuremind.ai_service.dto.ClauseContentDto;
import com.procuremind.ai_service.dto.TocNodeDto;
import com.procuremind.ai_service.service.AnalysisQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisQueryService queryService;

    @GetMapping("/{contractId}")
    public ResponseEntity<AnalysisResponseDto> getAnalysis(@PathVariable UUID contractId) {
        return queryService.getAnalysis(contractId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{contractId}/risks")
    public ResponseEntity<List<AnalysisResponseDto.RiskDto>> getRisks(@PathVariable UUID contractId) {
        return queryService.getRisks(contractId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{contractId}/toc")
    public ResponseEntity<List<TocNodeDto>> getTableOfContents(@PathVariable UUID contractId) {
        List<TocNodeDto> toc = queryService.getTableOfContent(contractId);

        if (toc.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toc);
    }

    @GetMapping("/node/{nodeId}")
    public ResponseEntity<ClauseContentDto> getClauseContent(@PathVariable UUID nodeId) {
        return queryService.getClauseContent(nodeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
