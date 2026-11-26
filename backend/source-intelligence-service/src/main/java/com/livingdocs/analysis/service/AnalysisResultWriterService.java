package com.livingdocs.analysis.service;

import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisCommand;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisOutcome;

/**
 * Conditional transactional writer for M4 verified analysis findings, target fences,
 * and lifecycle-guarded drift issues/observations.
 */
public interface AnalysisResultWriterService {

	PersistAnalysisOutcome applyResult(PersistAnalysisCommand command);
}
